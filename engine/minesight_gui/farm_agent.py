"""MineSight Farm Agent - the lightweight GUI for REMOTE farm machines.

Runs on any PC that should contribute game clients to a collection farm
hosted elsewhere. Needs only this repo, Python with PySide6 + psutil, and a
JDK 17+ for Gradle (Gradle then downloads Minecraft/Forge/JDK8 by itself).

    python -m minesight_gui.farm_agent      (or MineSight-Farm.bat)

Point it at the Control Panel host (Collector tab -> "Allow LAN clients"
shows the address), hit Launch, and the clients appear in the host's
Collector tab with a globe marker; captures stream back over the WebSocket.
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

from PySide6.QtCore import QSettings, QTimer
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .constants import MOD_DIR
from .procs import ManagedProcess
from .widgets import LogView

LAUNCH_STAGGER_MS = 20000


def _dark_palette() -> QPalette:
    p = QPalette()
    bg, panel, text = QColor(30, 31, 34), QColor(43, 45, 48), QColor(220, 220, 220)
    p.setColor(QPalette.ColorRole.Window, bg)
    p.setColor(QPalette.ColorRole.WindowText, text)
    p.setColor(QPalette.ColorRole.Base, panel)
    p.setColor(QPalette.ColorRole.Text, text)
    p.setColor(QPalette.ColorRole.Button, panel)
    p.setColor(QPalette.ColorRole.ButtonText, text)
    p.setColor(QPalette.ColorRole.Highlight, QColor(74, 237, 217))
    p.setColor(QPalette.ColorRole.HighlightedText, QColor(20, 20, 20))
    return p


class FarmAgentWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MineSight Farm Agent")
        self.resize(820, 560)
        self.settings = QSettings("MineSight", "FarmAgent")

        self._client_procs: list[tuple[int, ManagedProcess]] = []
        self._launch_queue: list[int] = []
        self._launch_timer = QTimer(self, interval=LAUNCH_STAGGER_MS)
        self._launch_timer.timeout.connect(self._launch_next)

        central = QWidget()
        layout = QVBoxLayout(central)
        self.setCentralWidget(central)

        box = QGroupBox("Farm settings")
        grid = QGridLayout(box)
        grid.addWidget(QLabel("Control Panel host:"), 0, 0)
        self.host = QLineEdit(self.settings.value("host", "ws://192.168.1.100:8766"))
        self.host.setToolTip(
            "Shown in the host's Collector tab when 'Allow LAN clients' is on,\n"
            "e.g. ws://192.168.1.42:8766"
        )
        grid.addWidget(self.host, 0, 1, 1, 3)
        grid.addWidget(QLabel("Clients:"), 1, 0)
        self.client_count = QSpinBox(minimum=1, maximum=6, value=int(self.settings.value("clients", 2)))
        grid.addWidget(self.client_count, 1, 1)
        grid.addWidget(QLabel("World base name:"), 1, 2)
        self.world_base = QLineEdit(self.settings.value("worldBase", "FarmWorld"))
        self.world_base.setToolTip("Client N auto-opens world <base>N, created on first launch")
        grid.addWidget(self.world_base, 1, 3)
        grid.addWidget(QLabel("Java home (17+, for Gradle):"), 2, 0)
        self.java_home = QLineEdit(self.settings.value("javaHome", os.environ.get("JAVA_HOME", "")))
        self.java_home.setToolTip(
            "Path to a JDK 17+ used to RUN Gradle (it provisions everything else).\n"
            "Leave as-is if JAVA_HOME already points at one."
        )
        grid.addWidget(self.java_home, 2, 1, 1, 2)
        self.mute = QCheckBox("Mute")
        self.mute.setChecked(self.settings.value("mute", True, type=bool))
        grid.addWidget(self.mute, 2, 3)
        layout.addWidget(box)

        controls = QHBoxLayout()
        self.launch_btn = QPushButton("🚀 Launch clients")
        self.launch_btn.clicked.connect(self.launch_clients)
        controls.addWidget(self.launch_btn)
        stop_btn = QPushButton("■ Stop all clients")
        stop_btn.clicked.connect(self.stop_clients)
        controls.addWidget(stop_btn)
        self.running_label = QLabel("0 running")
        controls.addWidget(self.running_label)
        controls.addStretch(1)
        layout.addLayout(controls)

        self.log = LogView()
        layout.addWidget(self.log, 1)
        self.log.append_line(
            "Remote worker for a MineSight collection farm. Set the host address, "
            "launch, then start the session from the HOST's Collector tab."
        )

    # --- launching (mirrors the Mod tab launcher, plus the host marker) -------

    def launch_clients(self) -> None:
        if self._launch_queue:
            return
        self.settings.setValue("host", self.host.text().strip())
        self.settings.setValue("clients", self.client_count.value())
        self.settings.setValue("worldBase", self.world_base.text().strip())
        self.settings.setValue("javaHome", self.java_home.text().strip())
        self.settings.setValue("mute", self.mute.isChecked())
        count = self.client_count.value()
        self._launch_queue = list(range(1, count + 1))
        self.log.append_line(
            f"[launching {count} client(s), {LAUNCH_STAGGER_MS // 1000}s apart - "
            "the FIRST launch downloads Minecraft/Forge and can take several minutes]"
        )
        self._launch_next()
        if self._launch_queue:
            self._launch_timer.start()

    def _launch_next(self) -> None:
        if not self._launch_queue:
            self._launch_timer.stop()
            return
        idx = self._launch_queue.pop(0)
        base = self.world_base.text().strip()
        host = self.host.text().strip()

        run_dir = MOD_DIR / f"run-client{idx}"
        run_dir.mkdir(parents=True, exist_ok=True)
        # Marker files: more reliable than forwarding JVM properties through gradle.
        (run_dir / "minesight-collector.txt").write_text(host + "\n")
        marker = run_dir / "minesight-autoworld.txt"
        if base:
            marker.write_text(f"{base}{idx}\n")
        elif marker.exists():
            marker.unlink()
        self._patch_options(run_dir, self.mute.isChecked())

        # Farm clients run the collector mod (core + collector). ../run-clientN
        # resolves to mod/run-clientN so the marker/options files line up.
        args = [
            "/c", "gradlew.bat", ":collector:runClient", "--console=plain",
            "--project-cache-dir", f".gradle-client{idx}",
            f"-Pminesight.runDir=../run-client{idx}",
        ]
        java_home = self.java_home.text().strip()
        if java_home:
            args.insert(2, f"-Dorg.gradle.java.home={java_home}")
        proc = ManagedProcess(self)
        proc.line.connect(lambda line, c=idx: self.log.append_line(f"[C{c}] {line}"))
        proc.started.connect(self._update_running)
        proc.finished.connect(lambda code, c=idx: (
            self.log.append_line(f"[client {c} exited ({code})]"), self._update_running()))
        proc.start("cmd.exe", args, str(MOD_DIR))
        self._client_procs.append((idx, proc))
        self.log.append_line(f"[client {idx} starting → run-client{idx}, host {host}]")
        self._update_running()
        if not self._launch_queue:
            self._launch_timer.stop()

    @staticmethod
    def _patch_options(run_dir: Path, mute: bool) -> None:
        opts = run_dir / "options.txt"
        value = "0.0" if mute else "1.0"
        lines = opts.read_text(encoding="utf-8", errors="replace").splitlines() if opts.exists() else []
        for i, line in enumerate(lines):
            if line.startswith("soundCategory_master:"):
                lines[i] = f"soundCategory_master:{value}"
                break
        else:
            lines.append(f"soundCategory_master:{value}")
        opts.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def stop_clients(self) -> None:
        self._launch_queue = []
        self._launch_timer.stop()
        for _idx, proc in self._client_procs:
            proc.stop()
        killed = 0
        try:
            import psutil

            for p in psutil.process_iter(["name"]):
                try:
                    if (p.info["name"] or "").lower() not in ("java.exe", "javaw.exe"):
                        continue
                    cwd = Path(p.cwd())
                    if cwd.parent == MOD_DIR and cwd.name.startswith("run-client"):
                        p.kill()
                        killed += 1
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue
        except Exception as e:
            self.log.append_line(f"[process cleanup failed: {e}]")
        self.log.append_line(f"[stopped - {killed} game process(es) terminated]")
        self._update_running()

    def _update_running(self) -> None:
        n = sum(1 for _idx, p in self._client_procs if p.running)
        self.running_label.setText(f"{n} running")

    def closeEvent(self, event) -> None:
        self.stop_clients()
        super().closeEvent(event)


def main() -> int:
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setPalette(_dark_palette())
    win = FarmAgentWindow()
    win.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
