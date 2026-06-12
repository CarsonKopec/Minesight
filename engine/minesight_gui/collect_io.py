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


def finalize(session_name: str, classes: list[str], seed: int = 42) -> Path:
    """Split the pool 80/10/10 (per the spec) and write data.yaml."""
    ds_dir = DATASETS_DIR / session_name
    pool = ds_dir / "pool"
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
    return ds_dir


def merge_into(src_ds: Path, dst_ds: Path) -> int:
    """Copy src images+labels into dst, remapping class indices by name.

    Every src class must exist in dst's names. Returns images copied.
    """
    src_cfg = yaml.safe_load((src_ds / "data.yaml").read_text(encoding="utf-8"))
    dst_cfg = yaml.safe_load((dst_ds / "data.yaml").read_text(encoding="utf-8"))
    src_names = [str(n) for n in src_cfg["names"]]
    dst_names = [str(n) for n in dst_cfg["names"]]
    missing = [n for n in src_names if n not in dst_names]
    if missing:
        raise ValueError(
            f"Classes {missing} don't exist in {dst_ds.name} - can't merge without them."
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
