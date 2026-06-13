from __future__ import annotations

import os
import shutil
import time
from pathlib import Path

from PySide6.QtCore import QSettings, QTimer, QUrl
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QCheckBox,
    QFileDialog,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .constants import MOD_DIR
from .procs import ManagedProcess
from .widgets import LogView

# Parallel gradle invocations need a head start for the first one to compile;
# later ones see everything up-to-date.
LAUNCH_STAGGER_MS = 20000


class ModTab(QWidget):
    """Build/install the Forge mod and launch one or more dev game clients."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.settings = QSettings("MineSight", "ControlPanel")
        self.proc = ManagedProcess(self)  # gradle build
        self.proc.line.connect(self._on_line)
        self.proc.started.connect(self._update_state)
        self.proc.finished.connect(self._on_build_finished)

        self._client_procs: list[tuple[int, ManagedProcess]] = []
        self._launch_queue: list[int] = []
        self._launch_timer = QTimer(self, interval=LAUNCH_STAGGER_MS)
        self._launch_timer.timeout.connect(self._launch_next)

        layout = QVBoxLayout(self)

        # --- jar build/install ------------------------------------------------
        build_row = QHBoxLayout()
        self.build_btn = QPushButton("🔨 Build jar")
        self.build_btn.clicked.connect(self._build)
        build_row.addWidget(self.build_btn)
        self.stop_btn = QPushButton("■ Stop build")
        self.stop_btn.setEnabled(False)
        self.stop_btn.clicked.connect(self.proc.stop)
        build_row.addWidget(self.stop_btn)
        self.test_btn = QPushButton("🎯 Launch test client")
        self.test_btn.setToolTip(
            "One normal game client for testing the ML model: start the engine on\n"
            "the Engine tab, join a world here, and watch the live detection overlay.\n"
            "Stays floating (not embedded) so the engine can screen-capture it."
        )
        self.test_btn.clicked.connect(self.launch_test_client)
        build_row.addWidget(self.test_btn)
        build_row.addStretch(1)
        self.install_btn = QPushButton("📦 Install to mods folder")
        self.install_btn.clicked.connect(self._install)
        build_row.addWidget(self.install_btn)
        open_libs = QPushButton("Open build/libs")
        open_libs.clicked.connect(
            lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(MOD_DIR / "build" / "libs")))
        )
        build_row.addWidget(open_libs)
        layout.addLayout(build_row)

        self.status = QLabel(self._jar_status())
        layout.addWidget(self.status)

        # --- multi-client launcher --------------------------------------------
        launcher = QGroupBox("Dev client launcher (collection farm)")
        lrow = QHBoxLayout(launcher)
        lrow.addWidget(QLabel("Clients:"))
        self.client_count = QSpinBox(minimum=1, maximum=6, value=int(self.settings.value("clientCount", 2)))
        self.client_count.setToolTip(
            "Each client runs in its own sandbox (run-clientN/) with its own world.\n"
            "2-4 is the practical limit on most machines - each wants ~2 CPU cores."
        )
        lrow.addWidget(self.client_count)
        lrow.addWidget(QLabel("World base name:"))
        self.world_base = QLineEdit(self.settings.value("worldBase", "MineSightData"))
        self.world_base.setToolTip(
            "Client N auto-opens world '<base>N' from the main menu, creating it\n"
            "(creative, random seed) on first launch. Leave empty to pick worlds manually."
        )
        lrow.addWidget(self.world_base, 1)
        self.mute = QCheckBox("Mute")
        self.mute.setChecked(self.settings.value("muteClients", True, type=bool))
        self.mute.setToolTip("Set each client's master volume to 0 in its options.txt before launch")
        lrow.addWidget(self.mute)
        self.launch_btn = QPushButton("🚀 Launch clients")
        self.launch_btn.clicked.connect(self.launch_clients)
        lrow.addWidget(self.launch_btn)
        self.stop_clients_btn = QPushButton("■ Stop all clients")
        self.stop_clients_btn.clicked.connect(self.stop_clients)
        lrow.addWidget(self.stop_clients_btn)
        self.running_label = QLabel("0 running")
        lrow.addWidget(self.running_label)
        layout.addWidget(launcher)

        self.log = LogView()
        layout.addWidget(self.log, 1)

    # --- build / install ------------------------------------------------------

    # The four module jars (final remapped output), one per subproject.
    _MODULES = ("core", "detection", "world", "collector")

    def _module_jars(self) -> list[Path]:
        jars = []
        for m in self._MODULES:
            libs = MOD_DIR / m / "build" / "libs"
            if not libs.exists():
                continue
            cands = [p for p in libs.glob(f"minesight{m}-*.jar")
                     if not any(t in p.name for t in ("-dev", "without-deps", "non-obfuscated"))]
            if cands:
                jars.append(max(cands, key=lambda p: p.stat().st_mtime))
        return jars

    def _jar_status(self) -> str:
        jars = self._module_jars()
        if jars:
            newest = max(j.stat().st_mtime for j in jars)
            built = time.strftime("%Y-%m-%d %H:%M", time.localtime(newest))
            return f"{len(jars)}/4 module jars built (latest {built})"
        return "No jars built yet."

    def _build(self) -> None:
        if self.proc.running:
            return
        self.log.append_line("$ gradlew build")
        self.proc.start("cmd.exe", ["/c", "gradlew.bat", "build", "--console=plain"], str(MOD_DIR))

    def _install(self) -> None:
        jars = self._module_jars()
        if not jars:
            self.status.setText("No jars to install - build first.")
            return
        default = self.settings.value(
            "modsDir", os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "mods")
        )
        target = QFileDialog.getExistingDirectory(self, "Choose mods folder", default)
        if not target:
            return
        self.settings.setValue("modsDir", target)
        # Remove older MineSight jars so two versions never load together.
        for old in Path(target).glob("minesight*.jar"):
            old.unlink()
        for jar in jars:
            shutil.copy2(jar, target)
        names = ", ".join(j.name for j in jars)
        self.status.setText(f"Installed {len(jars)} jar(s) → {target}")
        self.log.append_line(f"[installed to {target}: {names}]")
        if len(jars) < 4:
            self.log.append_line("[note: core is required; build all four with the Build button]")

    def _on_line(self, line: str) -> None:
        self.log.append_line(line)

    def _on_build_finished(self, code: int) -> None:
        self.log.append_line(f"[gradle exited with code {code}]")
        self.status.setText(self._jar_status())
        self._update_state()

    def _update_state(self) -> None:
        running = self.proc.running
        self.build_btn.setEnabled(not running)
        self.stop_btn.setEnabled(running)

    # --- client launcher --------------------------------------------------------

    def launch_clients(self) -> None:
        if self._launch_queue:
            return
        self.settings.setValue("worldBase", self.world_base.text().strip())
        self.settings.setValue("muteClients", self.mute.isChecked())
        self.settings.setValue("clientCount", self.client_count.value())
        count = self.client_count.value()
        self._launch_queue = list(range(1, count + 1))
        self.log.append_line(
            f"[launching {count} client(s), {LAUNCH_STAGGER_MS // 1000}s apart - "
            "the first launch may need to compile first]"
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
        # Farm clients run the collector mod only (core + collector). runDir is
        # ../run-clientN so it resolves to mod/run-clientN (the collector
        # subproject dir + ..), keeping marker/options paths at the mod root.
        args = [
            "/c", "gradlew.bat", ":collector:runClient", "--console=plain",
            # Separate project caches let gradle builds run in parallel.
            "--project-cache-dir", f".gradle-client{idx}",
            f"-Pminesight.runDir=../run-client{idx}",
        ]
        # The mod reads this file from its run dir and auto-opens the world;
        # a file is reliable where gradle property forwarding is not.
        run_dir = MOD_DIR / f"run-client{idx}"
        run_dir.mkdir(parents=True, exist_ok=True)
        marker = run_dir / "minesight-autoworld.txt"
        if base:
            marker.write_text(f"{base}{idx}\n")
        elif marker.exists():
            marker.unlink()
        self._patch_options(run_dir, self.mute.isChecked())
        proc = ManagedProcess(self)
        proc.line.connect(lambda line, c=idx: self.log.append_line(f"[C{c}] {line}"))
        proc.started.connect(self._update_running)
        proc.finished.connect(lambda code, c=idx: self._on_client_exit(c, code))
        proc.start("cmd.exe", args, str(MOD_DIR))
        self._client_procs.append((idx, proc))
        world = f"{base}{idx}" if base else "(manual world select)"
        self.log.append_line(f"[client {idx} starting → run-client{idx}, world {world}]")
        self._update_running()
        if not self._launch_queue:
            self._launch_timer.stop()

    @staticmethod
    def _patch_options(run_dir: Path, mute: bool) -> None:
        """Set the client's master volume in its sandboxed options.txt."""
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

    def _on_client_exit(self, idx: int, code: int) -> None:
        self.log.append_line(f"[client {idx} exited ({code})]")
        self._update_running()

    def launch_test_client(self) -> None:
        """One full play client (core + detection + world) for testing the model."""
        proc = ManagedProcess(self)
        proc.line.connect(lambda line: self.log.append_line(f"[TEST] {line}"))
        proc.finished.connect(lambda code: self.log.append_line(f"[test client exited ({code})]"))
        # :world:runClient pulls in detection + core; runDir ../run -> mod/run.
        proc.start("cmd.exe",
                   ["/c", "gradlew.bat", ":world:runClient", "--console=plain",
                    "-Pminesight.runDir=../run"],
                   str(MOD_DIR))
        self._client_procs.append((0, proc))
        self.log.append_line("[test client starting → mod/run (detection + world; pick a world in the menu)]")

    def stop_clients(self) -> None:
        self._launch_queue = []
        self._launch_timer.stop()
        for _idx, proc in self._client_procs:
            proc.stop()
        # Killing gradlew does NOT kill the game: the gradle DAEMON owns the
        # client JVM. Hunt down java processes living in our run dirs.
        killed = 0
        try:
            import psutil

            for p in psutil.process_iter(["name"]):
                try:
                    if (p.info["name"] or "").lower() not in ("java.exe", "javaw.exe"):
                        continue
                    cwd = Path(p.cwd())
                    if cwd.parent == MOD_DIR and (cwd.name.startswith("run-client") or cwd.name == "run"):
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

    def shutdown(self) -> None:
        self.stop_clients()
        self.proc.stop()
