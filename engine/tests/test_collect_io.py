"""Tests for the dataset pipeline: finalize, dedup, merge, rebalance."""
from __future__ import annotations

from collections import Counter

import pytest
import yaml

from conftest import make_dataset, make_image, write_label
from minesight_gui import collect_io


def _label_class_counts(ds, splits=("train", "valid", "test")) -> Counter:
    c: Counter = Counter()
    for split in splits:
        ld = ds / split / "labels"
        if not ld.exists():
            continue
        for lf in ld.glob("*.txt"):
            for line in lf.read_text().splitlines():
                if line.strip():
                    c[int(line.split()[0])] += 1
    return c


def test_finalize_splits_and_writes_yaml(datasets_dir):
    pool = collect_io.pool_dir("collected-a")
    for i in range(20):
        make_image(pool / "images" / f"img{i}.png", seed=i)
        write_label(pool / "labels" / f"img{i}.txt", [(i % 2, 0.5, 0.5, 0.1, 0.1)])

    ds, removed = collect_io.finalize("collected-a", ["gold_ore", "iron_ore"])

    total = sum(len(list((ds / s / "images").glob("*.png"))) for s in ("train", "valid", "test"))
    assert total == 20
    # every image keeps its label
    for s in ("train", "valid", "test"):
        imgs = {p.stem for p in (ds / s / "images").glob("*.png")}
        lbls = {p.stem for p in (ds / s / "labels").glob("*.txt")}
        assert imgs == lbls
    cfg = yaml.safe_load((ds / "data.yaml").read_text())
    assert cfg["nc"] == 2 and cfg["names"] == ["gold_ore", "iron_ore"]
    assert not (ds / "pool").exists()  # pool consumed


def test_finalize_empty_pool_raises(datasets_dir):
    (collect_io.pool_dir("empty") / "images").mkdir(parents=True)
    with pytest.raises(ValueError):
        collect_io.finalize("empty", ["gold_ore"])


def test_dedup_pool_keeps_richest(datasets_dir):
    pool = collect_io.pool_dir("dups")
    # base image
    make_image(pool / "images" / "a.png", seed=1)
    write_label(pool / "labels" / "a.txt", [(0, 0.5, 0.5, 0.1, 0.1)])
    # near-identical copy of a.png but with MORE boxes -> should replace a
    import shutil

    shutil.copy(pool / "images" / "a.png", pool / "images" / "b.png")
    write_label(pool / "labels" / "b.txt", [(0, 0.5, 0.5, 0.1, 0.1), (1, 0.2, 0.2, 0.1, 0.1)])
    # a distinct image -> kept
    make_image(pool / "images" / "c.png", seed=999)
    write_label(pool / "labels" / "c.txt", [(0, 0.4, 0.4, 0.2, 0.2)])

    removed = collect_io.dedup_pool(pool)
    remaining = sorted(p.name for p in (pool / "images").glob("*.png"))
    assert removed == 1
    assert "c.png" in remaining and "b.png" in remaining and "a.png" not in remaining


def test_merge_appends_unknown_classes_and_remaps(datasets_dir):
    src = make_dataset(datasets_dir / "src", ["gold_ore", "coal_ore"],
                       {"train": [("s0", [(1, 0.5, 0.5, 0.1, 0.1)])]})  # coal
    dst = make_dataset(datasets_dir / "dst", ["diamond_ore", "gold_ore"],
                       {"train": [("d0", [(0, 0.5, 0.5, 0.1, 0.1)])]})

    copied = collect_io.merge_into(src, dst)
    assert copied == 1
    names = yaml.safe_load((dst / "data.yaml").read_text())["names"]
    assert names == ["diamond_ore", "gold_ore", "coal_ore"]  # appended, order preserved
    # the copied coal box (src idx 1) remaps to coal's dst index (2)
    line = (dst / "train" / "labels" / "s0.txt").read_text().split()
    assert int(line[0]) == names.index("coal_ore")


def test_rebalance_spreads_rare_classes(datasets_dir):
    # 100 common (class 0) + 10 rare (class 1), all in train
    train = [(f"c{i}", [(0, 0.5, 0.5, 0.1, 0.1)]) for i in range(100)]
    train += [(f"r{i}", [(1, 0.5, 0.5, 0.1, 0.1)]) for i in range(10)]
    ds = make_dataset(datasets_dir / "skewed", ["common", "rare"], {"train": train})

    counts = collect_io.rebalance_splits(ds)
    assert sum(counts.values()) == 110
    assert 0.7 < counts["train"] / 110 < 0.88

    # rare class must appear in valid AND test now, not only train
    for split in ("valid", "test"):
        rare = _label_class_counts(ds, splits=(split,))[1]
        assert rare >= 1, f"rare class missing from {split}"
