from __future__ import annotations

import sys
from pathlib import Path

ENGINE_DIR = Path(__file__).resolve().parents[1]
ROOT_DIR = ENGINE_DIR.parent
MOD_DIR = ROOT_DIR / "mod"
DATASETS_DIR = ENGINE_DIR / "datasets"
RUNS_DIR = ENGINE_DIR / "runs"

PYTHON = sys.executable  # the venv interpreter the GUI itself runs in
WS_URL = "ws://127.0.0.1:8765"

STOCK_MODELS = ["yolo26s.pt", "yolo26n.pt", "yolo26m.pt"]
