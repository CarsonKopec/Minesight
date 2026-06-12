"""Build ore-detection-v3 from v1 + v2.

Background: v1 has the complete annotation set but noisy classes (11 classes
including a junk Roboflow-comment name at index 5, and stone/deepslate variants
mixed together). v2 is the curated remap (9 clean classes) but the remap
deleted ~1,014 boxes (mostly the junk-named class 5, which is deepslate iron).

v3 = v2's curated labels and split, plus the deleted v1 boxes restored.
Each restored box is classified by the v2-trained model (highest-IoU
prediction); boxes the model can't match default to deepslate_iron, matching
the manual remap decision made for the 89 class-5 boxes that survived into v2.

Usage (from engine/):
    python tools/build_v3_dataset.py
"""
from __future__ import annotations

import shutil
from collections import Counter
from pathlib import Path

from ultralytics import YOLO

ENGINE = Path(__file__).resolve().parent.parent
DATASETS = ENGINE / "datasets"
V1 = DATASETS / "ore-detection-v1"
V2 = DATASETS / "ore-detection-v2"
V3 = DATASETS / "ore-detection-v3"
V2_MODEL = ENGINE / "runs/detect/minesight_runs/yolo26s_ores-2/weights/best.pt"

NAMES = [
    "deepslate_diamond_ore",
    "deepslate_gold_ore",
    "deepslate_iron_ore",
    "deepslate_redstone_ore",
    "diamond_ore",
    "gold_ore",
    "iron_ore",
    "lapis_ore",
    "redstone_ore",
]
DEEPSLATE_IRON = 2  # default class for unmatched restored boxes

SPLITS = ("train", "valid", "test")
MATCH_TOL_XY = 0.01
MATCH_TOL_WH = 0.02
MIN_IOU = 0.40


def load_labels(folder: Path) -> dict[str, list[tuple]]:
    out: dict[str, list[tuple]] = {}
    for lf in folder.glob("*.txt"):
        boxes = []
        for line in lf.read_text().splitlines():
            t = line.split()
            if len(t) == 5:
                boxes.append((int(t[0]), float(t[1]), float(t[2]), float(t[3]), float(t[4])))
        out[lf.stem] = boxes
    return out


def find_image(images_dir: Path, stem: str) -> Path | None:
    for ext in (".jpg", ".png", ".jpeg"):
        p = images_dir / (stem + ext)
        if p.exists():
            return p
    return None


def iou(a, b) -> float:
    ax1, ay1, ax2, ay2 = a[1] - a[3] / 2, a[2] - a[4] / 2, a[1] + a[3] / 2, a[2] + a[4] / 2
    bx1, by1, bx2, by2 = b[0], b[1], b[2], b[3]
    ix = max(0.0, min(ax2, bx2) - max(ax1, bx1))
    iy = max(0.0, min(ay2, by2) - max(ay1, by1))
    inter = ix * iy
    union = a[3] * a[4] + (bx2 - bx1) * (by2 - by1) - inter
    return inter / union if union > 0 else 0.0


def main() -> None:
    # 1. Index all labels by stem (the image sets are identical across versions).
    v1_all: dict[str, list[tuple]] = {}
    v2_split_of: dict[str, str] = {}
    v2_all: dict[str, list[tuple]] = {}
    for split in SPLITS:
        v1_all.update(load_labels(V1 / split / "labels"))
        part = load_labels(V2 / split / "labels")
        v2_all.update(part)
        for stem in part:
            v2_split_of[stem] = split

    # 2. Find v1 boxes that have no coordinate-match in v2 (the deleted ones).
    deleted: dict[str, list[tuple]] = {}
    for stem, boxes1 in v1_all.items():
        boxes2 = v2_all.get(stem, [])
        for b1 in boxes1:
            matched = any(
                abs(b1[1] - b2[1]) < MATCH_TOL_XY
                and abs(b1[2] - b2[2]) < MATCH_TOL_XY
                and abs(b1[3] - b2[3]) < MATCH_TOL_WH
                and abs(b1[4] - b2[4]) < MATCH_TOL_WH
                for b2 in boxes2
            )
            if not matched:
                deleted.setdefault(stem, []).append(b1)
    n_deleted = sum(len(v) for v in deleted.values())
    print(f"Boxes to restore: {n_deleted} across {len(deleted)} images")

    # 3. Classify each deleted box with the v2-trained model.
    model = YOLO(str(V2_MODEL))
    assigned = Counter()
    restored: dict[str, list[tuple]] = {}
    for i, (stem, boxes) in enumerate(sorted(deleted.items())):
        split = v2_split_of.get(stem, "train")
        img = find_image(V1 / "train" / "images", stem) or find_image(
            V1 / v2_split_of.get(stem, "train") / "images", stem
        )
        if img is None:
            for s in SPLITS:
                img = find_image(V1 / s / "images", stem)
                if img:
                    break
        preds = []
        if img is not None:
            r = model.predict(str(img), conf=0.25, verbose=False)[0]
            preds = [
                (int(b.cls), *b.xyxyn[0].tolist(), float(b.conf)) for b in r.boxes
            ]
        for b1 in boxes:
            best_cls, best_iou = None, 0.0
            for cls, x1, y1, x2, y2, conf in preds:
                v = iou(b1, (x1, y1, x2, y2))
                if v > best_iou:
                    best_cls, best_iou = cls, v
            cls = best_cls if best_cls is not None and best_iou >= MIN_IOU else DEEPSLATE_IRON
            assigned[NAMES[cls]] += 1
            restored.setdefault(stem, []).append((cls, b1[1], b1[2], b1[3], b1[4]))
        if (i + 1) % 100 == 0:
            print(f"  classified {i + 1}/{len(deleted)} images...")

    print("Restored box classes:")
    for name, n in assigned.most_common():
        print(f"  {name}: {n}")

    # 4. Write v3: copy v2 images + labels, append restored boxes.
    if V3.exists():
        shutil.rmtree(V3)
    for split in SPLITS:
        (V3 / split / "labels").mkdir(parents=True)
        shutil.copytree(V2 / split / "images", V3 / split / "images")
        v2_labels = load_labels(V2 / split / "labels")
        for stem, boxes in v2_labels.items():
            extra = restored.get(stem, []) if v2_split_of.get(stem) == split else []
            lines = [
                f"{c} {x:.6f} {y:.6f} {w:.6f} {h:.6f}" for c, x, y, w, h in list(boxes) + list(extra)
            ]
            (V3 / split / "labels" / f"{stem}.txt").write_text("\n".join(lines) + ("\n" if lines else ""))

    yaml = (
        "train: ../train/images\nval: ../valid/images\ntest: ../test/images\n\n"
        f"nc: {len(NAMES)}\nnames: {NAMES}\n"
    )
    (V3 / "data.yaml").write_text(yaml)

    # 5. Health summary.
    for split in SPLITS:
        labels = load_labels(V3 / split / "labels")
        hist = Counter()
        empty = 0
        for boxes in labels.values():
            if not boxes:
                empty += 1
            for b in boxes:
                hist[b[0]] += 1
        total = sum(hist.values())
        print(f"v3 {split}: {len(labels)} label files ({empty} empty), {total} boxes, "
              f"hist={dict(sorted(hist.items()))}")
    print(f"\nDone: {V3 / 'data.yaml'}")


if __name__ == "__main__":
    main()
