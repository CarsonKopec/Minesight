"""Dataset health analysis: split stats, class balance, common labeling bugs."""
from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .constants import DATASETS_DIR

SPLITS = ("train", "valid", "test")


@dataclass
class SplitStats:
    images: int = 0
    label_files: int = 0
    empty: int = 0
    boxes: int = 0
    hist: Counter = field(default_factory=Counter)


@dataclass
class DatasetHealth:
    name: str
    path: Path
    names: list[str] = field(default_factory=list)
    nc: int = 0
    splits: dict[str, SplitStats] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)


def list_datasets() -> list[Path]:
    if not DATASETS_DIR.exists():
        return []
    return sorted(p.parent for p in DATASETS_DIR.glob("*/data.yaml"))


def analyze(dataset_dir: Path) -> DatasetHealth:
    h = DatasetHealth(name=dataset_dir.name, path=dataset_dir)
    try:
        cfg = yaml.safe_load((dataset_dir / "data.yaml").read_text(encoding="utf-8")) or {}
    except Exception as e:
        h.warnings.append(f"data.yaml unreadable: {e}")
        return h
    names = cfg.get("names") or []
    if isinstance(names, dict):
        names = [names[k] for k in sorted(names)]
    h.names = [str(n) for n in names]
    h.nc = int(cfg.get("nc") or len(h.names))

    max_cls_seen = -1
    for split in SPLITS:
        st = SplitStats()
        img_dir = dataset_dir / split / "images"
        lbl_dir = dataset_dir / split / "labels"
        if img_dir.exists():
            st.images = sum(1 for _ in img_dir.iterdir())
        if lbl_dir.exists():
            for lf in lbl_dir.glob("*.txt"):
                st.label_files += 1
                lines = [l for l in lf.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip()]
                if not lines:
                    st.empty += 1
                for line in lines:
                    try:
                        cls = int(line.split()[0])
                    except (ValueError, IndexError):
                        continue
                    st.hist[cls] += 1
                    st.boxes += 1
                    max_cls_seen = max(max_cls_seen, cls)
        h.splits[split] = st

    # Warnings
    if h.nc != len(h.names):
        h.warnings.append(f"nc={h.nc} but {len(h.names)} class names listed")
    for i, n in enumerate(h.names):
        low = n.lower()
        if "roboflow.com" in low or "exported" in low or len(n) > 60:
            h.warnings.append(f"class {i} looks like junk text: '{n[:50]}...'")
    if max_cls_seen >= max(h.nc, len(h.names)):
        h.warnings.append(f"labels use class index {max_cls_seen}, beyond the declared class count")
    norm = {}
    for i, n in enumerate(h.names):
        key = n.lower().replace(" ", "_").replace("-", "_").removeprefix("deepslate_")
        norm.setdefault(key, []).append(i)
    total = Counter()
    for st in h.splits.values():
        total.update(st.hist)
    for i, n in enumerate(h.names):
        if 0 < total.get(i, 0) < 20:
            h.warnings.append(f"class {i} '{n}' has only {total[i]} boxes - too few to learn")
        elif total.get(i, 0) == 0:
            h.warnings.append(f"class {i} '{n}' has no boxes at all")
    tr = h.splits.get("train")
    if tr and tr.label_files and tr.empty / tr.label_files > 0.6:
        h.warnings.append(f"train split is {tr.empty / tr.label_files:.0%} empty label files")
    va = h.splits.get("valid")
    if va and va.label_files and va.empty / va.label_files > 0.6:
        h.warnings.append(f"valid split is {va.empty / va.label_files:.0%} empty - validation metrics will be misleading")
    return h
