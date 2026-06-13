from __future__ import annotations

import logging

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QComboBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from . import version_switch

log = logging.getLogger("minesight.gui.version")


class VersionTab(QWidget):
    """Switch the whole project between release lines (git branches) via
    worktrees, then relaunch the Control Panel from the chosen version."""

    def __init__(self) -> None:
        super().__init__()
        layout = QVBoxLayout(self)
        layout.setAlignment(Qt.AlignmentFlag.AlignTop)
        layout.setSpacing(10)

        self.releases: list[dict] = []
        self.current = "?"
        try:
            self.releases = version_switch.load_releases()
            self.current = version_switch.current_ref()
        except Exception as e:  # git missing, not a repo, etc.
            layout.addWidget(QLabel(f"⚠ Version switching unavailable: {e}"))

        layout.addWidget(QLabel("<b>MineSight version</b>"))
        self.current_label = QLabel()
        layout.addWidget(self.current_label)

        row = QHBoxLayout()
        self.combo = QComboBox()
        for r in self.releases:
            self.combo.addItem(r["name"], r["ref"])
        row.addWidget(self.combo, 1)
        self.switch_btn = QPushButton("Switch && Restart")
        self.switch_btn.clicked.connect(self._switch)
        row.addWidget(self.switch_btn)
        layout.addLayout(row)

        self.desc = QLabel()
        self.desc.setWordWrap(True)
        layout.addWidget(self.desc)
        self.combo.currentIndexChanged.connect(self._update_desc)

        note = QLabel(
            "Switching opens the selected version in its own git worktree and "
            "relaunches the Control Panel from it — your current working tree is "
            "left untouched, and each version keeps its own built jars. Build "
            "that version's mod/plugin from the Mod tab after switching."
        )
        note.setWordWrap(True)
        note.setStyleSheet("color: #999;")
        layout.addWidget(note)

        self._refresh_current()
        self._update_desc()

    def _refresh_current(self) -> None:
        name = next((r["name"] for r in self.releases if r["ref"] == self.current), None)
        shown = name or self.current
        self.current_label.setText(
            f"Running: <b>{shown}</b>  (branch <code>{self.current}</code>)"
        )
        idx = self.combo.findData(self.current)
        if idx >= 0:
            self.combo.setCurrentIndex(idx)

    def _update_desc(self) -> None:
        ref = self.combo.currentData()
        r = next((x for x in self.releases if x["ref"] == ref), None)
        self.desc.setText(r.get("description", "") if r else "")
        self.switch_btn.setEnabled(ref is not None and ref != self.current)

    def _switch(self) -> None:
        ref = self.combo.currentData()
        if not ref or ref == self.current:
            return
        name = self.combo.currentText()
        confirm = QMessageBox.question(
            self,
            "Switch version",
            f"Switch to {name}?\n\nThe Control Panel will close and reopen "
            f"running that version. Anything running in other tabs will stop.",
        )
        if confirm != QMessageBox.StandardButton.Yes:
            return
        try:
            path = version_switch.switch(ref)
            log.info("Switching to %s, relaunching from %s", ref, path)
        except Exception as e:
            QMessageBox.critical(self, "Switch failed", str(e))
            return
        # The relaunch helper is detached and waits for us to exit (freeing the
        # engine/collector ports); close this Control Panel now.
        from PySide6.QtWidgets import QApplication

        QApplication.quit()

    def shutdown(self) -> None:
        pass
