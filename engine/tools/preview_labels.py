"""Draw YOLO labels onto their images so you can eyeball box alignment.

Used to verify the MineSight 2.0 capture pipeline: it projects known ore
world-AABBs to screen, so if the boxes here hug the ore, the projection matrix
(and therefore the screen<->world math the detection anchoring reuses) is
calibrated. If they're shifted/scaled, tune FOV/NEAR_PLANE in the client's
CaptureManager.

Usage:
    python -m engine.tools.preview_labels [POOL_DIR]

POOL_DIR is a folder containing images/ and labels/ subdirs. Defaults to the
farm-stream pool the 2.0 client streams into. Annotated copies are written to
POOL_DIR/preview/.
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

# Default to where the 2.0 GUI upload lands; override with an arg for the
# client-local dir (<.minecraft>/minesight/captures).
DEFAULT_POOL = Path(__file__).resolve().parents[1] / "datasets" / "farm-stream" / "pool"

# Distinct-ish colors per class index (cycled if more classes).
PALETTE = [
    (74, 237, 217), (46, 204, 64), (255, 215, 0), (216, 200, 184),
    (138, 138, 138), (255, 65, 54), (61, 90, 254), (224, 123, 79), (239, 230, 220),
]


def _load_classes(pool: Path) -> list[str]:
    f = pool / "classes.txt"
    if f.is_file():
        return [ln.strip() for ln in f.read_text(encoding="utf-8").splitlines() if ln.strip()]
    return []


def main() -> int:
    pool = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_POOL
    images = pool / "images"
    labels = pool / "labels"
    if not images.is_dir():
        print(f"No images/ under {pool} - run a capture batch first "
              f"(/msf scan <ore> then /msf capture <n>).")
        return 1

    classes = _load_classes(pool)
    out = pool / "preview"
    out.mkdir(parents=True, exist_ok=True)

    n = 0
    for img_path in sorted(images.glob("*.png")):
        label_path = labels / (img_path.stem + ".txt")
        if not label_path.is_file():
            continue
        img = Image.open(img_path).convert("RGB")
        draw = ImageDraw.Draw(img)
        w, h = img.size
        for line in label_path.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) != 5:
                continue
            cls = int(float(parts[0]))
            cx, cy, bw, bh = (float(v) for v in parts[1:])
            x0 = (cx - bw / 2) * w
            y0 = (cy - bh / 2) * h
            x1 = (cx + bw / 2) * w
            y1 = (cy + bh / 2) * h
            color = PALETTE[cls % len(PALETTE)]
            draw.rectangle([x0, y0, x1, y1], outline=color, width=2)
            name = classes[cls] if 0 <= cls < len(classes) else str(cls)
            draw.text((x0 + 2, max(0, y0 - 11)), name, fill=color)
        img.save(out / img_path.name)
        n += 1

    print(f"Annotated {n} image(s) -> {out}")
    if n:
        print("Open them and check each box hugs its ore. Offset/scale => tune "
              "FOV/NEAR_PLANE in client CaptureManager.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
