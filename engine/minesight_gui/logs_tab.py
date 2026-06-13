"""Logs tab: the Control Panel's own application log, live, with a DEBUG toggle.

A logging.Handler bridges Python's logging into a Qt widget on the GUI thread,
so log records from any thread show up safely. Full detail is always written to
engine/logs/control-panel.log via the shared file handler.
"""
from __future__ import annotations

import logging

from PySide6.QtCore import QObject, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QCheckBox,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from minesight.logconf import LOGS_DIR, set_console_level
from .widgets import LogView


class _Bridge(QObject):
    message = Signal(str)


class QtLogHandler(logging.Handler):
    """Forwards formatted log records to the Logs tab via a queued signal."""

    def __init__(self):
        super().__init__()
        self.bridge = _Bridge()
        self.set_name("minesight-gui")

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.bridge.message.emit(self.format(record))
        except Exception:
            pass


class LogsTab(QWidget):
    def __init__(self, handler: QtLogHandler, parent=None):
        super().__init__(parent)
        layout = QVBoxLayout(self)

        top = QHBoxLayout()
        self.debug_check = QCheckBox("Debug logging")
        self.debug_check.setToolTip(
            "Show DEBUG-level detail here and in the log file. Full detail is\n"
            "always written to engine/logs/ regardless."
        )
        self.debug_check.toggled.connect(self._toggle_debug)
        top.addWidget(self.debug_check)
        top.addStretch(1)
        top.addWidget(QLabel(f"→ {LOGS_DIR}"))
        open_btn = QPushButton("Open log folder")
        open_btn.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(LOGS_DIR))))
        top.addWidget(open_btn)
        clear_btn = QPushButton("Clear")
        clear_btn.clicked.connect(lambda: self.view.clear())
        top.addWidget(clear_btn)
        layout.addLayout(top)

        self.view = LogView()
        layout.addWidget(self.view, 1)

        handler.bridge.message.connect(self.view.append_line)

    def _toggle_debug(self, on: bool) -> None:
        set_console_level(on)
        logging.getLogger("minesight.gui").info("Debug logging %s", "ON" if on else "OFF")

    def shutdown(self) -> None:
        pass
