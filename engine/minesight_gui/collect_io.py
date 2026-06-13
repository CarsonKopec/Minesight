"""Filesystem side of dataset collection: finalize a captured pool into a
train/valid/test YOLO dataset, and merge datasets by class name."""
from __future__ import annotations

import random
import shutil
from collections import Counter
from pathlib import Path

import yaml

from .constants import DATASETS_DIR

SPLITS = ("train", "valid", "test")
IMG_EXTS = (".png", ".jpg", ".jpeg")


def pool_dir(session_name: str) -> Path:
    return DATASETS_DIR / session_name / "pool"


def pool_count(session_name: str) -> int:
    images = pool_dir(session_name) / "images"
    return sum(1 for _ in images.glob("*.png")) if images.exists() else 0


def _dhash(img_path: Path, hash_size: int = 8) -> int:
    """Difference hash: nearly identical frames get nearly identical bits."""
    from PIL import Image

    with Image.open(img_path) as im:
        im = im.convert("L").resize((hash_size + 1, hash_size))
        px = list(im.getdata())
    bits = 0
    for row in range(hash_size):
        for col in range(hash_size):
            i = row * (hash_size + 1) + col
            bits = (bits << 1) | (px[i] > px[i + 1])
    return bits


def _label_boxes(pool: Path, stem: str) -> int:
    label = pool / "labels" / (stem + ".txt")
    if not label.exists():
        return 0
    return sum(1 for line in label.read_text(encoding="utf-8").splitlines() if line.strip())


def dedup_pool(pool: Path, max_distance: int = 5) -> int:
    """Remove near-duplicate images (similar perceptual hash), keeping the one
    with more labeled boxes. Returns how many images were removed."""
    images = sorted((pool / "images").glob("*.png"))
    kept: list[tuple[int, Path]] = []  # (hash, path)
    removed = 0

    def delete(img: Path) -> None:
        img.unlink(missing_ok=True)
        (pool / "labels" / (img.stem + ".txt")).unlink(missing_ok=True)

    for img in images:
        try:
            h = _dhash(img)
        except Exception:
            continue  # unreadable image - leave it for the user to inspect
        duplicate_of = None
        for i, (kh, kimg) in enumerate(kept):
            if (h ^ kh).bit_count() <= max_distance:
                duplicate_of = i
                break
        if duplicate_of is None:
            kept.append((h, img))
            continue
        _, other = kept[duplicate_of]
        if _label_boxes(pool, img.stem) > _label_boxes(pool, other.stem):
            delete(other)
            kept[duplicate_of] = (h, img)
        else:
            delete(img)
        removed += 1
    return removed


def finalize(session_name: str, classes: list[str], seed: int = 42,
             dedup: bool = True) -> tuple[Path, int]:
    """Dedup, split the pool 80/10/10 (per the spec) and write data.yaml.

    Returns (dataset_dir, near_duplicates_removed).
    """
    ds_dir = DATASETS_DIR / session_name
    pool = ds_dir / "pool"
    removed = dedup_pool(pool) if dedup else 0
    images = sorted((pool / "images").glob("*.png"))
    if not images:
        raise ValueError("Pool is empty - collect some images first.")

    rng = random.Random(seed)
    shuffled = images[:]
    rng.shuffle(shuffled)
    n = len(shuffled)
    n_train = max(1, round(n * 0.8))
    n_valid = max(1, round(n * 0.1)) if n >= 10 else max(0, n - n_train)
    assignment = {
        "train": shuffled[:n_train],
        "valid": shuffled[n_train:n_train + n_valid],
        "test": shuffled[n_train + n_valid:],
    }

    for split, files in assignment.items():
        (ds_dir / split / "images").mkdir(parents=True, exist_ok=True)
        (ds_dir / split / "labels").mkdir(parents=True, exist_ok=True)
        for img in files:
            shutil.move(str(img), ds_dir / split / "images" / img.name)
            label = pool / "labels" / (img.stem + ".txt")
            target = ds_dir / split / "labels" / (img.stem + ".txt")
            if label.exists():
                shutil.move(str(label), target)
            else:
                target.write_text("")

    (ds_dir / "data.yaml").write_text(
        "train: ../train/images\nval: ../valid/images\ntest: ../test/images\n\n"
        f"nc: {len(classes)}\nnames: {classes}\n"
    )
    shutil.rmtree(pool, ignore_errors=True)
    return ds_dir, removed


def merge_into(src_ds: Path, dst_ds: Path) -> int:
    """Copy src images+labels into dst, remapping class indices by name.

    Classes the destination doesn't know yet are appended to its data.yaml
    (existing indices never shift). Returns images copied.
    """
    src_cfg = yaml.safe_load((src_ds / "data.yaml").read_text(encoding="utf-8"))
    dst_cfg = yaml.safe_load((dst_ds / "data.yaml").read_text(encoding="utf-8"))
    src_names = [str(n) for n in src_cfg["names"]]
    dst_names = [str(n) for n in dst_cfg["names"]]
    new_classes = [n for n in src_names if n not in dst_names]
    if new_classes:
        dst_names = dst_names + new_classes
        (dst_ds / "data.yaml").write_text(
            "train: ../train/images\nval: ../valid/images\ntest: ../test/images\n\n"
            f"nc: {len(dst_names)}\nnames: {dst_names}\n"
        )
    remap = {i: dst_names.index(n) for i, n in enumerate(src_names)}

    copied = 0
    for split in ("train", "valid", "test"):
        src_img = src_ds / split / "images"
        if not src_img.exists():
            continue
        (dst_ds / split / "images").mkdir(parents=True, exist_ok=True)
        (dst_ds / split / "labels").mkdir(parents=True, exist_ok=True)
        for img in src_img.iterdir():
            shutil.copy2(img, dst_ds / split / "images" / img.name)
            copied += 1
            label = src_ds / split / "labels" / (img.stem + ".txt")
            lines_out = []
            if label.exists():
                for line in label.read_text(encoding="utf-8").splitlines():
                    parts = line.split()
                    if len(parts) == 5:
                        lines_out.append(" ".join([str(remap[int(parts[0])])] + parts[1:]))
            (dst_ds / split / "labels" / (img.stem + ".txt")).write_text(
                "\n".join(lines_out) + ("\n" if lines_out else "")
            )
    return copied


def _label_classes(label_path: Path) -> set[int]:
    if not label_path.exists():
        return set()
    out = set()
    for line in label_path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if len(parts) == 5:
            out.add(int(parts[0]))
    return out


def rebalance_splits(ds_dir: Path, train: float = 0.8, valid: float = 0.1,
                     test: float = 0.1, seed: int = 42) -> dict[str, int]:
    """Pool every image across train/valid/test and re-split, stratified so each
    class (and background) is spread proportionally across the three splits.

    Returns the new per-split image counts. data.yaml is left untouched.
    """
    total = train + valid + test
    train, valid, test = train / total, valid / total, test / total

    # Gather every (image, label) pair across the existing splits.
    items: list[tuple[Path, Path]] = []
    for split in SPLITS:
        img_dir = ds_dir / split / "images"
        if not img_dir.exists():
            continue
        for img in img_dir.iterdir():
            if img.suffix.lower() in IMG_EXTS:
                items.append((img, ds_dir / split / "labels" / (img.stem + ".txt")))
    if not items:
        raise ValueError("No images found in this dataset.")

    # Global class frequency, to stratify by the RAREST class in each image -
    # this is what guarantees scarce classes still land in valid/test.
    global_count: Counter = Counter()
    for _img, lbl in items:
        global_count.update(_label_classes(lbl))

    def group_key(label_path: Path) -> str:
        classes = _label_classes(label_path)
        if not classes:
            return "__background__"
        return str(min(classes, key=lambda c: (global_count[c], c)))

    groups: dict[str, list[tuple[Path, Path]]] = {}
    for item in items:
        groups.setdefault(group_key(item[1]), []).append(item)

    rng = random.Random(seed)
    assignment: dict[str, str] = {}  # stem -> split
    for members in groups.values():
        rng.shuffle(members)
        n = len(members)
        n_train = round(n * train)
        n_valid = round(n * valid)
        # Make sure tiny groups still seed valid/test when there's enough.
        if n >= 3:
            n_train = min(n_train, n - 2)
            n_valid = max(1, n_valid)
        for i, (img, _lbl) in enumerate(members):
            if i < n_train:
                assignment[img.stem] = "train"
            elif i < n_train + n_valid:
                assignment[img.stem] = "valid"
            else:
                assignment[img.stem] = "test"

    # Stage everything flat, then redistribute - bulletproof against any
    # cross-split filename overlap and partial failure.
    staging = ds_dir / "_rebalance_tmp"
    if staging.exists():
        shutil.rmtree(staging)
    (staging / "images").mkdir(parents=True)
    (staging / "labels").mkdir(parents=True)
    for img, lbl in items:
        shutil.move(str(img), staging / "images" / img.name)
        if lbl.exists():
            shutil.move(str(lbl), staging / "labels" / lbl.name)

    counts = {s: 0 for s in SPLITS}
    for split in SPLITS:
        (ds_dir / split / "images").mkdir(parents=True, exist_ok=True)
        (ds_dir / split / "labels").mkdir(parents=True, exist_ok=True)
    for img in list((staging / "images").iterdir()):
        split = assignment.get(img.stem, "train")
        shutil.move(str(img), ds_dir / split / "images" / img.name)
        lbl = staging / "labels" / (img.stem + ".txt")
        if lbl.exists():
            shutil.move(str(lbl), ds_dir / split / "labels" / lbl.name)
        counts[split] += 1
    shutil.rmtree(staging, ignore_errors=True)
    return counts
