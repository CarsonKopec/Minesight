"""Shared logging setup for the engine and the Control Panel.

Rotating debug logs land in engine/logs/<name>.log (full DEBUG detail), while
the console / GUI show INFO by default. Set MINESIGHT_DEBUG=1 (or pass debug=
True / the GUI's Debug toggle) to surface DEBUG everywhere.
"""
from __future__ import annotations

import logging
import os
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOGS_DIR = Path(__file__).resolve().parent.parent / "logs"
_FORMAT = "%(asctime)s %(levelname)-7s %(name)s: %(message)s"
_DATEFMT = "%H:%M:%S"
_configured: set[str] = set()


def env_debug() -> bool:
    return os.environ.get("MINESIGHT_DEBUG", "").lower() not in ("", "0", "false", "no")


def setup_logging(name: str, *, debug: bool | None = None, console: bool = True,
                  extra_handler: logging.Handler | None = None) -> logging.Logger:
    """Configure the root logger once per process. Idempotent."""
    if debug is None:
        debug = env_debug()
    console_level = logging.DEBUG if debug else logging.INFO

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)  # handlers filter; root passes everything
    fmt = logging.Formatter(_FORMAT, _DATEFMT)

    if name not in _configured:
        # Third-party libraries are extremely chatty at DEBUG; keep their noise
        # out of our logs (file and console) while ours stays at DEBUG.
        for noisy in ("matplotlib", "PIL", "websockets", "asyncio", "urllib3",
                      "ultralytics", "git", "fontTools"):
            logging.getLogger(noisy).setLevel(logging.INFO)
        try:
            LOGS_DIR.mkdir(parents=True, exist_ok=True)
            fh = RotatingFileHandler(
                LOGS_DIR / f"{name}.log", maxBytes=2_000_000, backupCount=3, encoding="utf-8"
            )
            fh.setLevel(logging.DEBUG)
            fh.setFormatter(fmt)
            fh.set_name("minesight-file")
            root.addHandler(fh)
        except Exception:
            pass  # logging must never crash the app
        if console:
            ch = logging.StreamHandler()
            ch.setLevel(console_level)
            ch.setFormatter(fmt)
            ch.set_name("minesight-console")
            root.addHandler(ch)
        _configured.add(name)

    if extra_handler is not None:
        extra_handler.setFormatter(fmt)
        root.addHandler(extra_handler)

    logging.getLogger("minesight").debug("Logging initialized (debug=%s) -> %s", debug, LOGS_DIR)
    return root


def set_console_level(debug: bool) -> None:
    """Flip the console/GUI handlers between INFO and DEBUG at runtime."""
    level = logging.DEBUG if debug else logging.INFO
    for h in logging.getLogger().handlers:
        if h.get_name() in ("minesight-console", "minesight-gui"):
            h.setLevel(level)
