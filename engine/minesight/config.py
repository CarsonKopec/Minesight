from __future__ import annotations

import argparse
from dataclasses import dataclass


@dataclass
class Config:
    weights: str = "yolo26s.pt"
    host: str = "127.0.0.1"
    port: int = 8765
    imgsz: int = 640
    conf: float = 0.40
    window_title: str = "Minecraft"
    device: str | None = None  # None -> ultralytics auto-selects (CUDA if available)
    track: bool = True  # persistent object IDs via ByteTrack (Phase 2)
    half: bool = False  # FP16 inference - faster on RTX GPUs
    debug_view: bool = False
    monitor: int = 1  # mss monitor index used when the window is not found


def parse_args(argv: list[str] | None = None) -> Config:
    d = Config()
    p = argparse.ArgumentParser(
        prog="minesight",
        description="MineSight ML engine (Phase 1): screen capture -> YOLO26 -> WebSocket",
    )
    p.add_argument(
        "--weights",
        default=d.weights,
        help="YOLO weights (.pt). Use your trained ore model; stock yolo26s.pt "
        "only knows COCO classes and is just a plumbing test.",
    )
    p.add_argument("--host", default=d.host, help="WebSocket bind address")
    p.add_argument("--port", type=int, default=d.port, help="WebSocket port")
    p.add_argument("--imgsz", type=int, default=d.imgsz, help="Inference resolution (640 or 1280)")
    p.add_argument("--conf", type=float, default=d.conf, help="Confidence threshold")
    p.add_argument(
        "--window-title",
        default=d.window_title,
        help="Substring of the Minecraft window title to capture",
    )
    p.add_argument("--device", default=d.device, help="torch device, e.g. 0 or cpu (default: auto)")
    p.add_argument(
        "--no-track",
        dest="track",
        action="store_false",
        help="Disable cross-frame tracking (objects get throwaway IDs)",
    )
    p.add_argument(
        "--half",
        action="store_true",
        help="FP16 inference - roughly 1.5-2x faster on RTX GPUs",
    )
    p.add_argument(
        "--debug-view",
        action="store_true",
        help="Show an OpenCV window with drawn detections (validate without the mod; q to quit)",
    )
    p.add_argument(
        "--monitor",
        type=int,
        default=d.monitor,
        help="mss monitor index fallback when the Minecraft window is not found",
    )
    a = p.parse_args(argv)
    return Config(
        weights=a.weights,
        host=a.host,
        port=a.port,
        imgsz=a.imgsz,
        conf=a.conf,
        window_title=a.window_title,
        device=a.device,
        track=a.track,
        half=a.half,
        debug_view=a.debug_view,
        monitor=a.monitor,
    )
