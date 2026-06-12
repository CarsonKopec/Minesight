"""Scanning and parsing of ultralytics training runs under runs/."""
from __future__ import annotations

import csv
from dataclasses import dataclass, field
from pathlib import Path

import yaml

from .constants import RUNS_DIR


@dataclass
class RunInfo:
    name: str
    run_dir: Path
    model: str = ""
    data: str = ""
    epochs_planned: int = 0
    epochs_done: int = 0
    map50: float | None = None
    map50_95: float | None = None
    best: Path | None = None
    mtime: float = 0.0
    history: list[dict] = field(default_factory=list)


def load_results(run_dir: Path) -> list[dict]:
    results = run_dir / "results.csv"
    if not results.exists():
        return []
    with results.open(newline="", encoding="utf-8") as f:
        return [{k.strip(): v.strip() for k, v in row.items() if k} for row in csv.DictReader(f)]


def _f(row: dict, key: str) -> float | None:
    try:
        return float(row[key])
    except (KeyError, ValueError, TypeError):
        return None


def scan_runs() -> list[RunInfo]:
    out: list[RunInfo] = []
    if not RUNS_DIR.exists():
        return out
    for args_file in RUNS_DIR.rglob("args.yaml"):
        run_dir = args_file.parent
        try:
            args = yaml.safe_load(args_file.read_text(encoding="utf-8")) or {}
        except Exception:
            continue
        if args.get("mode") not in (None, "train"):
            continue
        info = RunInfo(name=run_dir.name, run_dir=run_dir)
        info.model = Path(str(args.get("model", ""))).name
        info.data = str(args.get("data", ""))
        info.epochs_planned = int(args.get("epochs") or 0)
        info.mtime = args_file.stat().st_mtime
        best = run_dir / "weights" / "best.pt"
        info.best = best if best.exists() else None
        rows = load_results(run_dir)
        info.history = rows
        info.epochs_done = len(rows)
        if rows:
            info.map50 = _f(rows[-1], "metrics/mAP50(B)")
            info.map50_95 = _f(rows[-1], "metrics/mAP50-95(B)")
            info.mtime = (run_dir / "results.csv").stat().st_mtime
        out.append(info)
    out.sort(key=lambda r: (r.map50 is not None, r.map50 or 0.0), reverse=True)
    return out
