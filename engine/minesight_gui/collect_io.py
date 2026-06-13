"""Filesystem side of dataset collection: finalize a captured pool into a
train/valid/test YOLO dataset, and merge datasets by class name."""
from __future__ import annotations

import random
import shutil
from pathlib import Path

import yaml

from .constants import DATASETS_DIR


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
