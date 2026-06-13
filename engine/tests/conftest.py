"""Shared fixtures + helpers for MineSight tests.

Everything runs against temp dirs - no test ever touches engine/datasets/,
engine/review/, or a real model.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pytest
from PIL import Image


def make_image(path: Path, seed: int = 0, size=(64, 48)) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    arr = (rng.random((size[1], size[0], 3)) * 255).astype("uint8")
    Image.fromarray(arr).save(path)


def write_label(path: Path, boxes: list[tuple[int, float, float, float, float]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "".join(f"{c} {x:.6f} {y:.6f} {w:.6f} {h:.6f}\n" for c, x, y, w, h in boxes),
        encoding="utf-8",
    )


def make_pool(root: Path, name: str, items: list[tuple[str, list]], seed_base: int = 0) -> Path:
    """items: (stem, boxes) pairs; empty boxes -> background frame."""
    pool = root / name / "pool"
    for i, (stem, boxes) in enumerate(items):
        make_image(pool / "images" / f"{stem}.png", seed=seed_base + i)
        write_label(pool / "labels" / f"{stem}.txt", boxes)
    return pool


def make_dataset(ds: Path, classes: list[str], split_items: dict[str, list]) -> Path:
    """split_items: {'train': [(stem, boxes), ...], 'valid': [...], 'test': [...]}"""
    for split, items in split_items.items():
        for i, (stem, boxes) in enumerate(items):
            make_image(ds / split / "images" / f"{stem}.png", seed=i)
            write_label(ds / split / "labels" / f"{stem}.txt", boxes)
    (ds / "data.yaml").write_text(
        f"train: ../train/images\nval: ../valid/images\ntest: ../test/images\n\n"
        f"nc: {len(classes)}\nnames: {classes}\n",
        encoding="utf-8",
    )
    return ds


@pytest.fixture
def datasets_dir(tmp_path, monkeypatch):
    """A temp datasets root with collect_io pointed at it."""
    from minesight_gui import collect_io

    d = tmp_path / "datasets"
    d.mkdir()
    monkeypatch.setattr(collect_io, "DATASETS_DIR", d)
    return d
