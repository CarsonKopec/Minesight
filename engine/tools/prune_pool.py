"""Retroactive QA for collected pools: apply the same per-box pixel checks the
mod (>=0.7.1) performs at capture time, so data collected with older versions
gets cleaned to the same standard.

Per labeled box: the region must be bright enough (mean luma), textured enough
(luma std), and not dominated by lava colors (the old flowing-lava label bug).
Boxes that fail are removed; images left with no boxes are deleted.

Usage (from engine/):
    python tools/prune_pool.py datasets/collected-xxx [more pools...]
    python tools/prune_pool.py --all          # every datasets/*/pool
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
from PIL import Image

MIN_MEAN_LUMA = 8.0
MIN_STD_LUMA = 3.5
MAX_LAVA_FRACTION = 0.55


def box_ok(img: np.ndarray, cx: float, cy: float, w: float, h: float) -> tuple[bool, str]:
    height, width = img.shape[:2]
    x0 = max(0, int((cx - w / 2) * width))
    y0 = max(0, int((cy - h / 2) * height))
    x1 = min(width, int((cx + w / 2) * width))
    y1 = min(height, int((cy + h / 2) * height))
    if x1 - x0 < 2 or y1 - y0 < 2:
        return False, "degenerate"
    region = img[y0:y1, x0:x1].astype(np.float32)
    luma = region[..., 0] * 0.299 + region[..., 1] * 0.587 + region[..., 2] * 0.114
    if luma.mean() < MIN_MEAN_LUMA:
        return False, "too dark"
    if luma.std() < MIN_STD_LUMA:
        return False, "featureless (sky/fog/unrendered)"
    lava = (
        (region[..., 0] > 200) & (region[..., 1] > 60) & (region[..., 1] < 170) & (region[..., 2] < 80)
    )
    if lava.mean() > MAX_LAVA_FRACTION:
        return False, "mostly lava pixels"
    return True, ""


def prune(pool: Path) -> tuple[int, int]:
    """Returns (boxes_removed, images_deleted)."""
    images_dir = pool / "images"
    labels_dir = pool / "labels"
    boxes_removed = 0
    images_deleted = 0
    for img_path in sorted(images_dir.glob("*.png")):
        label_path = labels_dir / (img_path.stem + ".txt")
        if not label_path.exists():
            continue
        lines = [l for l in label_path.read_text(encoding="utf-8").splitlines() if l.strip()]
        if not lines:
            continue  # deliberate negative - leave it
        try:
            img = np.asarray(Image.open(img_path).convert("RGB"))
        except Exception:
            continue
        kept = []
        for line in lines:
            parts = line.split()
            if len(parts) != 5:
                continue
            ok, reason = box_ok(img, *(float(v) for v in parts[1:]))
            if ok:
                kept.append(line)
            else:
                boxes_removed += 1
                print(f"  drop box ({reason}): {img_path.name}")
        if not kept:
            img_path.unlink()
            label_path.unlink()
            images_deleted += 1
            print(f"  delete image (no boxes left): {img_path.name}")
        elif len(kept) != len(lines):
            label_path.write_text("\n".join(kept) + "\n", encoding="utf-8")
    return boxes_removed, images_deleted


def main() -> None:
    datasets = Path(__file__).resolve().parent.parent / "datasets"
    if "--all" in sys.argv:
        pools = sorted(p for p in datasets.glob("*/pool") if (p / "images").exists())
    else:
        pools = [Path(a) / "pool" if not a.endswith("pool") else Path(a) for a in sys.argv[1:]]
    total_b = total_i = 0
    for pool in pools:
        if not (pool / "images").exists():
            continue
        print(f"== {pool.parent.name} ==")
        b, i = prune(pool)
        total_b += b
        total_i += i
        print(f"  removed {b} box(es), deleted {i} image(s)")
    print(f"\nTotal: {total_b} boxes removed, {total_i} images deleted")


if __name__ == "__main__":
    main()
