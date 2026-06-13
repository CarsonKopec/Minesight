"""Tests for the active-learning review capture (engine side)."""
from __future__ import annotations

import json

import numpy as np

from minesight import review
from minesight.server import DetectionServer


def test_save_review_writes_png_and_predictions(tmp_path, monkeypatch):
    monkeypatch.setattr(review, "REVIEW_DIR", tmp_path / "review")
    frame = (np.random.default_rng(0).random((48, 64, 3)) * 255).astype("uint8")
    objs = [{"label": "redstone_ore", "x": 30, "y": 20, "w": 10, "h": 10, "confidence": 0.42, "id": 3}]

    stem = review.save_review(frame, objs, "manual")

    assert stem and stem.endswith("_manual")
    png = review.REVIEW_DIR / f"{stem}.png"
    meta = json.loads((review.REVIEW_DIR / f"{stem}.json").read_text())
    assert png.exists()
    assert meta["reason"] == "manual"
    assert meta["frame_w"] == 64 and meta["frame_h"] == 48
    assert meta["predictions"][0]["label"] == "redstone_ore"


def test_server_capture_review_uses_latest_frame(tmp_path, monkeypatch):
    monkeypatch.setattr(review, "REVIEW_DIR", tmp_path / "review")
    srv = DetectionServer("127.0.0.1", 0)
    srv.review_callback = review.save_review
    srv.latest_frame = (np.random.default_rng(1).random((32, 32, 3)) * 255).astype("uint8")
    srv.latest_objects = [{"label": "diamond_ore", "x": 16, "y": 16, "w": 8, "h": 8,
                           "confidence": 0.9, "id": 1}]

    stem = srv.capture_review("manual")
    assert stem is not None
    meta = json.loads((review.REVIEW_DIR / f"{stem}.json").read_text())
    assert meta["predictions"][0]["label"] == "diamond_ore"


def test_capture_review_without_frame_returns_none(tmp_path, monkeypatch):
    monkeypatch.setattr(review, "REVIEW_DIR", tmp_path / "review")
    srv = DetectionServer("127.0.0.1", 0)
    srv.review_callback = review.save_review
    # no latest_frame set
    assert srv.capture_review("manual") is None
