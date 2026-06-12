"""YOLO26 inference wrapper with cross-frame tracking (Phase 2)."""
from __future__ import annotations

from ultralytics import YOLO


class Detector:
    def __init__(
        self,
        weights: str,
        imgsz: int,
        conf: float,
        device: str | None = None,
        track: bool = True,
        half: bool = False,
    ):
        self.model = YOLO(weights)
        self.imgsz = imgsz
        self.conf = conf
        self.device = device
        # Phase 2: ByteTrack (the modern successor of the spec's SORT) gives
        # objects persistent IDs across frames - stable, flicker-free overlays.
        self.track = track
        # FP16 inference: ~1.5-2x faster on RTX tensor cores, same accuracy.
        self.half = half
        self._next_id = 0  # fallback for untracked detections

    def infer(self, frame_bgr) -> list[dict]:
        """Detections for one frame, in capture-frame pixels (x/y = box center)."""
        kwargs = dict(
            imgsz=self.imgsz,
            conf=self.conf,
            device=self.device,
            half=self.half,
            verbose=False,
        )
        if self.track:
            result = self.model.track(frame_bgr, persist=True, tracker="bytetrack.yaml", **kwargs)[0]
        else:
            result = self.model.predict(frame_bgr, **kwargs)[0]
        names = result.names
        objects = []
        for box in result.boxes:
            cx, cy, w, h = box.xywh[0].tolist()
            if box.id is not None:
                obj_id = int(box.id)
            else:
                obj_id = self._next_id
                self._next_id += 1
            objects.append(
                {
                    "label": names[int(box.cls)],
                    "x": round(cx, 1),
                    "y": round(cy, 1),
                    "w": round(w, 1),
                    "h": round(h, 1),
                    "confidence": round(float(box.conf), 3),
                    "id": obj_id,
                }
            )
        return objects
