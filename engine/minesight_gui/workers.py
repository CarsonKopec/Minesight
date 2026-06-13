"""Run blocking work off the Qt UI thread so the Control Panel never freezes.

Heavy dataset operations (rebalance, merge, dedup, health analysis over
thousands of label files) would otherwise lock up the window. Task wraps a
plain callable in a QThread and reports back via signals on the GUI thread.
"""
from __future__ import annotations

import logging

from PySide6.QtCore import QThread, Signal

log = logging.getLogger("minesight.gui.workers")


class Task(QThread):
    """Runs fn(*args, **kwargs) in a background thread.

    Emits done(result) on success or failed(message) on error - both delivered
    on the GUI thread, so slots can safely touch widgets.
    """

    done = Signal(object)
    failed = Signal(str)

    def __init__(self, fn, *args, parent=None, **kwargs):
        super().__init__(parent)
        self._fn = fn
        self._args = args
        self._kwargs = kwargs

    def run(self) -> None:
        try:
            result = self._fn(*self._args, **self._kwargs)
        except Exception as e:  # noqa: BLE001 - surfaced to the user
            log.exception("Background task %s failed", getattr(self._fn, "__name__", self._fn))
            self.failed.emit(str(e))
        else:
            self.done.emit(result)
