"""Active-learning capture: save a frame + the model's predictions for human
correction. The GUI's Review tab loads these, the tester fixes the labels, and
the result feeds back into the dataset - the highest-value training data there
is, because it targets the model's actual mistakes."""
from __future__ import annotations

import json
import time
from pathlib import Path

import cv2

ENGINE_DIR = Path(__file__).resolve().parent.parent
REVIEW_DIR = ENGINE_DIR / "review"


def save_review(frame_bgr, objects: list[dict], reason: str) -> str | None:
    """Write <stem>.png + <stem>.json (model predictions) into engine/review/.

    Returns the stem, or None on failure. Safe to call from any thread.
    """
    try:
        REVIEW_DIR.mkdir(parents=True, exist_ok=True)
        stem = f"review_{int(time.time() * 1000)}_{reason}"
        cv2.imwrite(str(REVIEW_DIR / f"{stem}.png"), frame_bgr)
        h, w = frame_bgr.shape[:2]
        meta = {
            "reason": reason,
            "frame_w": w,
            "frame_h": h,
            "ts": int(time.time() * 1000),
            # Model predictions in pixel coords (x/y = box center), to be corrected
            "predictions": objects,
        }
        (REVIEW_DIR / f"{stem}.json").write_text(json.dumps(meta), encoding="utf-8")
        return stem
    except Exception:
        return None
