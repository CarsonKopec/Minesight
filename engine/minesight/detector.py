"""YOLO26 inference wrapper."""
from __future__ import annotations

from ultralytics import YOLO


class Detector:
    def __init__(self, weights: str, imgsz: int, conf: float, device: str | None = None):
        self.model = YOLO(weights)
        self.imgsz = imgsz
        self.conf = conf
        self.device = device
        # Sequential IDs are enough for Phase 1. Phase 2 replaces this with
        # SORT/DeepSORT so IDs persist across frames.
        self._next_id = 0

    def infer(self, frame_bgr) -> list[dict]:
        """Detections for one frame, in capture-frame pixels (x/y = box center)."""
        result = self.model.predict(
            frame_bgr,
            imgsz=self.imgsz,
            conf=self.conf,
            device=self.device,
            verbose=False,
        )[0]
        names = result.names
        objects = []
        for box in result.boxes:
            cx, cy, w, h = box.xywh[0].tolist()
            objects.append(
                {
                    "label": names[int(box.cls)],
                    "x": round(cx, 1),
                    "y": round(cy, 1),
                    "w": round(w, 1),
                    "h": round(h, 1),
                    "confidence": round(float(box.conf), 3),
                    "id": self._next_id,
                }
            )
            self._next_id += 1
        return objects
