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
from .logconf import setup_logging
from .review import save_review
from .server import DetectionServer

log = logging.getLogger("minesight")

_PREVIEW_INTERVAL_S = 1 / 12  # cap GUI preview at ~12 FPS
_PREVIEW_MAX_W = 960
_STATS_INTERVAL_S = 2.0
# Auto-review: a detection whose confidence lands in this band is one the model
# is unsure about - exactly the frames worth correcting.
_UNCERTAIN_LO = 0.30
_UNCERTAIN_HI = 0.55
_AUTO_REVIEW_COOLDOWN_S = 8.0


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


class _FramePipe:
    """Hands frames from the capture thread to the inference thread, one frame
    ahead at most: the producer grabs frame N+1 while the consumer runs
    inference on N, so throughput is max(capture, inference) not their sum.
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._frame = None
        self._using_window = False
        self._ready = threading.Event()
        self._taken = threading.Event()
        self._taken.set()  # allow the first grab
        self._closed = False

    def publish(self, frame, using_window) -> None:
        # Wait until the consumer has taken the previous frame (stay <=1 ahead).
        while not self._taken.wait(0.2):
            if self._closed:
                return
        if self._closed:
            return
        with self._lock:
            self._frame = frame
            self._using_window = using_window
        self._taken.clear()
        self._ready.set()

    def take(self, timeout: float = 1.0):
        if not self._ready.wait(timeout):
            return None, False
        with self._lock:
            frame, using_window = self._frame, self._using_window
        self._ready.clear()
        self._taken.set()  # let the producer grab the next frame while we work
        return frame, using_window

    def close(self) -> None:
        self._closed = True
        self._taken.set()
        self._ready.set()


def _capture_loop(capture: WindowCapture, pipe: _FramePipe, stop: threading.Event) -> None:
    try:
        while not stop.is_set():
            frame = capture.grab()
            pipe.publish(frame, capture.using_window)
    except Exception:
        log.exception("Capture thread crashed")
    finally:
        pipe.close()


def _pipeline(cfg: Config, server: DetectionServer, loop, stop: threading.Event) -> None:
    try:
        _pipeline_loop(cfg, server, loop, stop)
    except Exception:
        log.exception("Pipeline crashed")


def _pipeline_loop(cfg: Config, server: DetectionServer, loop, stop: threading.Event) -> None:
    capture = WindowCapture(cfg.window_title, cfg.monitor)
    detector = Detector(cfg.weights, cfg.imgsz, cfg.conf, cfg.device, cfg.track, cfg.half)

    # Active learning: the server saves the current frame on demand (hotkey/GUI).
    server.review_callback = save_review

    # Capture runs in its own thread so screen-grab overlaps GPU inference.
    pipe = _FramePipe()
    cap_thread = threading.Thread(
        target=_capture_loop, args=(capture, pipe, stop), name="minesight-capture", daemon=True
    )
    cap_thread.start()

    frames = 0
    t0 = time.monotonic()
    fps = 0.0
    last_preview = 0.0
    last_stats = 0.0
    last_auto_review = 0.0
    warned_fallback = False

    # No artificial FPS cap: the spec's 10-30 FPS limit is not needed on a 4060 Ti.
    while not stop.is_set():
        frame, using_window = pipe.take(timeout=1.0)
        if frame is None:
            continue  # no frame yet (startup) or shutting down
        if not using_window and not warned_fallback:
            log.warning(
                "Minecraft window '%s' not found - capturing monitor %d instead",
                cfg.window_title,
                cfg.monitor,
            )
            warned_fallback = True

        t_grab = time.monotonic()
        objects = detector.infer(frame)
        if log.isEnabledFor(logging.DEBUG):
            infer_ms = (time.monotonic() - t_grab) * 1000
            log.debug("frame %dx%d | infer %.1f ms | %d det", frame.shape[1],
                      frame.shape[0], infer_ms, len(objects))
        h, w = frame.shape[:2]
        message = {
            "type": "detections",
            "objects": objects,
            "frame_w": w,
            "frame_h": h,
            "ts": int(time.time() * 1000),
        }
        asyncio.run_coroutine_threadsafe(server.broadcast(message), loop)

        # Expose the live frame so a flagged-mistake hotkey can grab it.
        server.latest_frame = frame
        server.latest_objects = objects

        frames += 1
        now = time.monotonic()

        # Auto-flag uncertain frames for review (rate-limited).
        if cfg.auto_review and now - last_auto_review >= _AUTO_REVIEW_COOLDOWN_S:
            if any(_UNCERTAIN_LO <= o["confidence"] <= _UNCERTAIN_HI for o in objects):
                last_auto_review = now
                save_review(frame, objects, "auto")
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
                "window_found": using_window,
            }
            asyncio.run_coroutine_threadsafe(server.broadcast_preview(stats), loop)

        if cfg.debug_view:
            cv2.imshow("MineSight debug (q to quit)", _draw_boxes(frame.copy(), objects))
            if cv2.waitKey(1) & 0xFF == ord("q"):
                stop.set()

    pipe.close()
    cap_thread.join(timeout=2.0)
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
    cfg = parse_args(argv)
    setup_logging("engine", debug=cfg.debug)
    log.info("MineSight engine starting (weights=%s, imgsz=%d, track=%s, half=%s)",
             cfg.weights, cfg.imgsz, cfg.track, cfg.half)

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
