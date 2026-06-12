"""MineSight ML engine entry point (Phase 1).

Pipeline: capture screen -> YOLO26 inference -> broadcast detections over WebSocket.

Run:
    python -m minesight --weights path/to/best.pt
    python -m minesight --debug-view          # validate detection without the mod
"""
from __future__ import annotations

import asyncio
import base64
import logging
import threading
import time
from pathlib import Path

import cv2

from .capture import WindowCapture
from .config import Config, parse_args
from .detector import Detector
from .server import DetectionServer

log = logging.getLogger("minesight")

_PREVIEW_INTERVAL_S = 1 / 12  # cap GUI preview at ~12 FPS
_PREVIEW_MAX_W = 960
_STATS_INTERVAL_S = 2.0


def _draw_boxes(frame, objects):
    for o in objects:
        p1 = (int(o["x"] - o["w"] / 2), int(o["y"] - o["h"] / 2))
        p2 = (int(o["x"] + o["w"] / 2), int(o["y"] + o["h"] / 2))
        cv2.rectangle(frame, p1, p2, (0, 255, 0), 2)
        cv2.putText(
            frame,
            f"{o['label']} {o['confidence']:.2f}",
            (p1[0], max(12, p1[1] - 6)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.5,
            (0, 255, 0),
            1,
        )
    return frame


def _gpu_check() -> None:
    import torch

    available = torch.cuda.is_available()
    log.info("CUDA available: %s", available)
    if available:
        log.info("GPU: %s", torch.cuda.get_device_name(0))
    else:
        log.warning(
            "Running on CPU - expect low FPS. Install CUDA-enabled PyTorch (see README)."
        )


def _pipeline(cfg: Config, server: DetectionServer, loop, stop: threading.Event) -> None:
    try:
        _pipeline_loop(cfg, server, loop, stop)
    except Exception:
        log.exception("Pipeline crashed")


def _pipeline_loop(cfg: Config, server: DetectionServer, loop, stop: threading.Event) -> None:
    capture = WindowCapture(cfg.window_title, cfg.monitor)
    detector = Detector(cfg.weights, cfg.imgsz, cfg.conf, cfg.device, cfg.track, cfg.half)

    frames = 0
    t0 = time.monotonic()
    fps = 0.0
    last_preview = 0.0
    last_stats = 0.0
    warned_fallback = False

    # No artificial FPS cap: the spec's 10-30 FPS limit is not needed on a 4060 Ti.
    while not stop.is_set():
        frame = capture.grab()
        if not capture.using_window and not warned_fallback:
            log.warning(
                "Minecraft window '%s' not found - capturing monitor %d instead",
                cfg.window_title,
                cfg.monitor,
            )
            warned_fallback = True

        objects = detector.infer(frame)
        h, w = frame.shape[:2]
        message = {
            "type": "detections",
            "objects": objects,
            "frame_w": w,
            "frame_h": h,
            "ts": int(time.time() * 1000),
        }
        asyncio.run_coroutine_threadsafe(server.broadcast(message), loop)

        frames += 1
        now = time.monotonic()
        if now - t0 >= 5.0:
            fps = frames / (now - t0)
            log.info(
                "%.1f FPS | %d object(s) in last frame | %d mod client(s)",
                fps,
                len(objects),
                server.mod_clients,
            )
            frames = 0
            t0 = now
        elif fps == 0.0 and now - t0 > 0:
            fps = frames / (now - t0)  # early estimate before the first window

        if server.preview_clients and now - last_preview >= _PREVIEW_INTERVAL_S:
            last_preview = now
            view = _draw_boxes(frame.copy(), objects)
            if view.shape[1] > _PREVIEW_MAX_W:
                scale = _PREVIEW_MAX_W / view.shape[1]
                view = cv2.resize(view, (_PREVIEW_MAX_W, int(view.shape[0] * scale)))
            ok, buf = cv2.imencode(".jpg", view, [cv2.IMWRITE_JPEG_QUALITY, 70])
            if ok:
                preview = {
                    "type": "preview",
                    "jpg": base64.b64encode(buf).decode("ascii"),
                    "w": view.shape[1],
                    "h": view.shape[0],
                }
                asyncio.run_coroutine_threadsafe(server.broadcast_preview(preview), loop)

        if server.preview_clients and now - last_stats >= _STATS_INTERVAL_S:
            last_stats = now
            stats = {
                "type": "stats",
                "fps": round(fps, 1),
                "objects": len(objects),
                "mod_clients": server.mod_clients,
                "window_found": capture.using_window,
            }
            asyncio.run_coroutine_threadsafe(server.broadcast_preview(stats), loop)

        if cfg.debug_view:
            cv2.imshow("MineSight debug (q to quit)", _draw_boxes(frame.copy(), objects))
            if cv2.waitKey(1) & 0xFF == ord("q"):
                stop.set()

    if cfg.debug_view:
        cv2.destroyAllWindows()


async def _run(cfg: Config) -> None:
    _gpu_check()
    server = DetectionServer(cfg.host, cfg.port)
    await server.start()

    loop = asyncio.get_running_loop()
    stop = threading.Event()
    worker = threading.Thread(
        target=_pipeline,
        args=(cfg, server, loop, stop),
        name="minesight-pipeline",
        daemon=True,
    )
    worker.start()
    log.info("Engine running. Waiting for the mod to connect... (Ctrl+C to quit)")
    try:
        while worker.is_alive():
            await asyncio.sleep(0.5)
    finally:
        stop.set()


def main(argv: list[str] | None = None) -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )
    cfg = parse_args(argv)

    # A bare model name (yolo26s.pt) is auto-downloaded by ultralytics, but an
    # explicit path that doesn't exist deserves a clear error, not a traceback.
    weights = Path(cfg.weights)
    if len(weights.parts) > 1 and not weights.exists():
        log.error("Weights file not found: %s", weights.resolve())
        for root in ("runs", "minesight_runs"):
            if Path(root).is_dir():
                for c in sorted(Path(root).rglob("best.pt")):
                    log.error("  trained weights found at: %s", c)
        raise SystemExit(1)

    try:
        asyncio.run(_run(cfg))
    except KeyboardInterrupt:
        log.info("Shutting down.")
