"""Embeds running Minecraft client windows as tabs inside the Control Panel,
so a collection farm doesn't litter the desktop with floating windows."""
from __future__ import annotations

import os
import sys

from PySide6.QtCore import QTimer, Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

if sys.platform == "win32":
    import win32con
    import win32gui
    import win32process

GAME_PROCESSES = ("java.exe", "javaw.exe")
SCAN_INTERVAL_MS = 3000


def _window_info(hwnd) -> dict | None:
    """Identify a top-level Minecraft window and how it was launched."""
    try:
        from pathlib import Path

        import psutil

        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        proc = psutil.Process(pid)
        if proc.name().lower() not in GAME_PROCESSES:
            return None
        world = None
        is_farm = False
        for arg in proc.cmdline():
            if arg.startswith("-Dminesight.autoworld="):
                world = arg.split("=", 1)[1]
                is_farm = True
                break
        # Launcher-spawned clients run inside mod/run-clientN and carry an
        # autoworld marker file there.
        try:
            cwd = Path(proc.cwd())
            if cwd.name.startswith("run-client"):
                is_farm = True
                marker = cwd / "minesight-autoworld.txt"
                if world is None and marker.is_file():
                    world = marker.read_text(encoding="utf-8").strip() or None
                if world is None:
                    world = cwd.name
        except Exception:
            pass
        return {"hwnd": hwnd, "pid": pid, "world": world, "is_farm": is_farm}
    except Exception:
        return None


def find_minecraft_windows() -> list[dict]:
    """Top-level Minecraft windows owned by Java processes."""
    found: list[dict] = []

    def cb(hwnd, _):
        if win32gui.IsWindowVisible(hwnd) and "minecraft" in win32gui.GetWindowText(hwnd).lower():
            info = _window_info(hwnd)
            if info:
                found.append(info)
        return True

    win32gui.EnumWindows(cb, None)
    return found


class EmbeddedGameHost(QWidget):
    """Hosts one reparented native game window, kept sized to this widget."""

    def __init__(self, hwnd: int, parent=None):
        super().__init__(parent)
        self.hwnd = hwnd
        self._orig_style = win32gui.GetWindowLong(hwnd, win32con.GWL_STYLE)
        style = self._orig_style
        style &= ~(win32con.WS_CAPTION | win32con.WS_THICKFRAME | win32con.WS_POPUP)
        style |= win32con.WS_CHILD
        win32gui.SetWindowLong(hwnd, win32con.GWL_STYLE, style)
        win32gui.SetParent(hwnd, int(self.winId()))
        self._fit()

    def alive(self) -> bool:
        return bool(win32gui.IsWindow(self.hwnd))

    def _fit(self) -> None:
        if not self.alive():
            return
        ratio = self.devicePixelRatioF()
        w = max(1, int(self.width() * ratio))
        h = max(1, int(self.height() * ratio))
        win32gui.SetWindowPos(
            self.hwnd, 0, 0, 0, w, h,
            win32con.SWP_NOZORDER | win32con.SWP_FRAMECHANGED | win32con.SWP_SHOWWINDOW,
        )

    def resizeEvent(self, event) -> None:
        super().resizeEvent(event)
        self._fit()

    def showEvent(self, event) -> None:
        super().showEvent(event)
        self._fit()

    def release(self) -> None:
        """Put the game window back on the desktop, frame restored."""
        if not self.alive():
            return
        win32gui.SetParent(self.hwnd, 0)
        win32gui.SetWindowLong(self.hwnd, win32con.GWL_STYLE, self._orig_style)
        win32gui.SetWindowPos(
            self.hwnd, 0, 80, 80, 1280, 760,
            win32con.SWP_NOZORDER | win32con.SWP_FRAMECHANGED | win32con.SWP_SHOWWINDOW,
        )


class ClientsTab(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self._hosts: dict[int, EmbeddedGameHost] = {}  # hwnd -> host

        layout = QVBoxLayout(self)
        top = QHBoxLayout()
        self.auto_embed = QCheckBox("Auto-embed farm clients")
        self.auto_embed.setChecked(True)
        self.auto_embed.setToolTip(
            "Clients started by the Mod tab launcher are pulled in automatically.\n"
            "Manually launched games are only listed - embed them with the button."
        )
        top.addWidget(self.auto_embed)
        self.embed_btn = QPushButton("Embed detected window")
        self.embed_btn.clicked.connect(self._embed_detected)
        top.addWidget(self.embed_btn)
        release_btn = QPushButton("Release current")
        release_btn.clicked.connect(self._release_current)
        top.addWidget(release_btn)
        release_all = QPushButton("Release all")
        release_all.clicked.connect(self.release_all)
        top.addWidget(release_all)
        top.addStretch(1)
        self.status = QLabel("")
        top.addWidget(self.status)
        layout.addLayout(top)

        note = QLabel(
            "ℹ Keep a window floating (released) if the detection engine should screen-capture it - "
            "embedded windows are invisible to the engine's window finder."
        )
        note.setWordWrap(True)
        note.setStyleSheet("color:#999;")
        layout.addWidget(note)

        self.tabs = QTabWidget()
        self.tabs.setTabsClosable(True)
        self.tabs.tabCloseRequested.connect(self._release_index)
        layout.addWidget(self.tabs, 1)

        self._timer = QTimer(self, interval=SCAN_INTERVAL_MS)
        self._timer.timeout.connect(self._scan)
        # Never grab real game windows from a headless/offscreen test run.
        if sys.platform == "win32" and os.environ.get("QT_QPA_PLATFORM") != "offscreen":
            self._timer.start()
            self._scan()
        else:
            self.status.setText("Window embedding inactive (non-Windows or headless).")

    # --- scanning -------------------------------------------------------------

    def _scan(self) -> None:
        # Drop tabs whose game has exited.
        for hwnd, host in list(self._hosts.items()):
            if not host.alive():
                idx = self.tabs.indexOf(host)
                if idx >= 0:
                    self.tabs.removeTab(idx)
                host.deleteLater()
                del self._hosts[hwnd]

        windows = [w for w in find_minecraft_windows() if w["hwnd"] not in self._hosts]
        farm = [w for w in windows if w["is_farm"]]
        other = [w for w in windows if not w["is_farm"]]

        if self.auto_embed.isChecked():
            for w in farm:
                self._embed(w)
            farm = []

        pending = farm + other
        self.embed_btn.setEnabled(bool(pending))
        if pending:
            self.status.setText(f"{len(pending)} floating game window(s) detected")
        else:
            self.status.setText(f"{len(self._hosts)} embedded")

    def _embed(self, info: dict) -> None:
        host = EmbeddedGameHost(info["hwnd"])
        self._hosts[info["hwnd"]] = host
        label = info["world"] or f"Minecraft (pid {info['pid']})"
        self.tabs.addTab(host, f"🎮 {label}")
        self.tabs.setCurrentWidget(host)

    def _embed_detected(self) -> None:
        for w in find_minecraft_windows():
            if w["hwnd"] not in self._hosts:
                self._embed(w)
        self._scan()

    # --- releasing -------------------------------------------------------------

    def _release_index(self, index: int) -> None:
        host = self.tabs.widget(index)
        if isinstance(host, EmbeddedGameHost):
            host.release()
            self.tabs.removeTab(index)
            self._hosts.pop(host.hwnd, None)
            host.deleteLater()

    def _release_current(self) -> None:
        idx = self.tabs.currentIndex()
        if idx >= 0:
            self._release_index(idx)

    def release_all(self) -> None:
        while self.tabs.count():
            self._release_index(0)

    def shutdown(self) -> None:
        # Closing the panel must never take the games down with it.
        self.release_all()
