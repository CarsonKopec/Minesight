"""Screen capture of the Minecraft window via mss.

Finds the Minecraft window by title substring and grabs its client area, so
detection pixel coordinates line up 1:1 with the mod's framebuffer. Falls back
to a full-monitor grab if the window can't be found.
"""
from __future__ import annotations

import ctypes
import logging
import sys
import time

import mss
import numpy as np

log = logging.getLogger("minesight.capture")

_WINDOW_REFRESH_S = 2.0

if sys.platform == "win32":
    import win32gui
    import win32process

    # Without DPI awareness, window rects come back in scaled coordinates and
    # the capture region won't line up with the actual pixels.
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:
        pass


def _process_name(hwnd) -> str:
    try:
        import psutil

        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        return psutil.Process(pid).name().lower()
    except Exception:
        return ""


def _find_window(title_substring: str):
    """hwnd of the best visible window whose title contains the substring.

    Other windows can match by accident (an editor with a "Minecraft" folder
    open, a browser tab...), so windows owned by a Java process - the actual
    game - win over plain title matches.
    """
    matches: list[int] = []

    def cb(hwnd, _):
        if win32gui.IsWindowVisible(hwnd):
            title = win32gui.GetWindowText(hwnd)
            if title_substring.lower() in title.lower():
                matches.append(hwnd)
        return True

    win32gui.EnumWindows(cb, None)
    if not matches:
        return None
    for hwnd in matches:
        if _process_name(hwnd) in ("javaw.exe", "java.exe"):
            return hwnd
    return matches[0]


class WindowCapture:
    """Grabs BGR frames of the Minecraft window's client area."""

    def __init__(self, window_title: str, fallback_monitor: int = 1):
        self.window_title = window_title
        self.fallback_monitor = fallback_monitor
        self._sct = mss.mss()
        self._hwnd = None
        self._last_find = 0.0
        self.using_window = False  # whether the last grab hit the real window

    def _client_rect(self) -> dict | None:
        # Re-locate the window occasionally; it can be moved, resized or closed.
        now = time.monotonic()
        if self._hwnd is None or now - self._last_find > _WINDOW_REFRESH_S:
            hwnd = _find_window(self.window_title)
            if hwnd is not None and hwnd != self._hwnd:
                log.info("Capturing window: '%s'", win32gui.GetWindowText(hwnd))
            self._hwnd = hwnd
            self._last_find = now
        if self._hwnd is None:
            return None
        try:
            left, top = win32gui.ClientToScreen(self._hwnd, (0, 0))
            l, t, r, b = win32gui.GetClientRect(self._hwnd)
            w, h = r - l, b - t
            if w < 16 or h < 16:  # minimized
                return None
            return {"left": left, "top": top, "width": w, "height": h}
        except Exception:
            self._hwnd = None
            return None

    def grab(self) -> np.ndarray:
        """One BGR frame (uint8, HxWx3)."""
        region = self._client_rect() if sys.platform == "win32" else None
        self.using_window = region is not None
        if region is None:
            region = self._sct.monitors[self.fallback_monitor]
        shot = self._sct.grab(region)
        frame = np.asarray(shot)[:, :, :3]  # BGRA -> BGR
        return np.ascontiguousarray(frame)
