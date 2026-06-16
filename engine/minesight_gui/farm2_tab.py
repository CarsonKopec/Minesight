from __future__ import annotations

import logging
from pathlib import Path

from PySide6.QtCore import QSettings, QTimer
from PySide6.QtWidgets import (
    QCheckBox,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .constants import CLIENT_DIR, ENGINE_DIR, PLUGIN_DIR, PYTHON
from .procs import ManagedProcess
from .widgets import LogView

# Clients launched well apart: each runClient holds the shared fabric-loom cache
# lock while it builds, and Minecraft's GLFW init is fragile when several windows
# come up at once - so give each one room to finish building + open before the
# next starts.
LAUNCH_STAGGER_MS = 30000

log = logging.getLogger("minesight.gui.farm2")


class Farm2Tab(QWidget):
    """Build and run the MineSight 2.0 stack: the Folia/Paper server plugin
    (gradlew runServer) and one or more Fabric dev clients (gradlew runClient)."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.settings = QSettings("MineSight", "ControlPanel")

        self.build_proc = ManagedProcess(self)
        self.build_proc.line.connect(self._on_line)
        self.build_proc.started.connect(self._update_state)
        self.build_proc.finished.connect(self._on_build_finished)

        self.server_proc = ManagedProcess(self)
        self.server_proc.line.connect(lambda ln: self.log.append_line(f"[server] {ln}"))
        self.server_proc.started.connect(self._update_state)
        self.server_proc.finished.connect(self._on_server_exit)

        self._client_procs: list[tuple[int, ManagedProcess]] = []
        self._launch_queue: list[int] = []
        self._launch_timer = QTimer(self, interval=LAUNCH_STAGGER_MS)
        self._launch_timer.timeout.connect(self._launch_next)

        # The CMA-ES optimizer process (python -m minesight.evolve).
        self.train_proc = ManagedProcess(self)
        self.train_proc.line.connect(lambda ln: self.log.append_line(f"[tune] {ln}"))
        self.train_proc.started.connect(self._update_state)
        self.train_proc.finished.connect(self._on_train_exit)

        layout = QVBoxLayout(self)

        # --- server -----------------------------------------------------------
        server = QGroupBox("Folia / Paper server (plugin)")
        srow = QHBoxLayout(server)
        self.build_btn = QPushButton("🔨 Build plugin + client")
        self.build_btn.clicked.connect(self._build)
        srow.addWidget(self.build_btn)
        self.start_server_btn = QPushButton("▶ Start server")
        self.start_server_btn.clicked.connect(self.start_server)
        srow.addWidget(self.start_server_btn)
        self.stop_server_btn = QPushButton("■ Stop server")
        self.stop_server_btn.clicked.connect(self.stop_server)
        srow.addWidget(self.stop_server_btn)
        self.server_status = QLabel("server: stopped")
        srow.addWidget(self.server_status, 1)
        layout.addWidget(server)

        note = QLabel(
            "First start writes plugin/run/eula.txt — set eula=true (and "
            "online-mode=false in plugin/run/server.properties for a no-account "
            "dev server), then Start again. runServer auto-builds the plugin."
        )
        note.setWordWrap(True)
        note.setStyleSheet("color: #999;")
        layout.addWidget(note)

        # --- clients ----------------------------------------------------------
        clients = QGroupBox("Fabric clients")
        crow = QHBoxLayout(clients)
        crow.addWidget(QLabel("Clients:"))
        self.client_count = QSpinBox(minimum=1, maximum=6,
                                     value=int(self.settings.value("farm2/clientCount", 1)))
        self.client_count.setToolTip(
            "Each client runs in its own run-clientN/ sandbox. 2-4 is the "
            "practical limit on most machines."
        )
        crow.addWidget(self.client_count)
        self.autojoin = QCheckBox("Auto-join")
        self.autojoin.setChecked(self.settings.value("farm2/autojoin", True, type=bool))
        self.autojoin.setToolTip("Quick-play straight into the server on launch instead of the menu.")
        crow.addWidget(self.autojoin)
        self.mute = QCheckBox("Mute")
        self.mute.setChecked(self.settings.value("farm2/mute", True, type=bool))
        self.mute.setToolTip("Set master volume to 0 in each client's options.txt before launch.")
        crow.addWidget(self.mute)
        crow.addWidget(QLabel("Server:"))
        self.server_addr = QLineEdit(self.settings.value("farm2/serverAddr", "localhost"))
        self.server_addr.setToolTip("Address the clients auto-join (host or host:port).")
        crow.addWidget(self.server_addr, 1)
        self.test_btn = QPushButton("🎯 Test client")
        self.test_btn.setToolTip(
            "Launch a single client in client/run for testing - watch the live\n"
            "detection overlay, or eyeball capture. Auto-joins if the box above is\n"
            "ticked. Runs alongside the farm clients (its own run dir)."
        )
        self.test_btn.clicked.connect(self.launch_test_client)
        crow.addWidget(self.test_btn)
        self.launch_btn = QPushButton("🚀 Launch clients")
        self.launch_btn.clicked.connect(self.launch_clients)
        crow.addWidget(self.launch_btn)
        self.stop_clients_btn = QPushButton("■ Stop clients")
        self.stop_clients_btn.clicked.connect(self.stop_clients)
        crow.addWidget(self.stop_clients_btn)
        self.running_label = QLabel("0 running")
        crow.addWidget(self.running_label)
        layout.addWidget(clients)

        # --- headless auto-tuner ----------------------------------------------
        train = QGroupBox("Headless training (CMA-ES auto-tuner)")
        trow = QHBoxLayout(train)
        trow.addWidget(QLabel("Arenas:"))
        self.arena_count = QSpinBox(minimum=1, maximum=999,
                                    value=int(self.settings.value("farm2/arenaCount", 4)))
        self.arena_count.setToolTip(
            "Bots training in parallel, one per arena (/msf train start N). Up to "
            "1024 slots exist; each bot pathfinds every tick, so a single server "
            "tops out well before 999 - boost /tick rate or use a real Folia jar."
        )
        trow.addWidget(self.arena_count)
        trow.addWidget(QLabel("Gens:"))
        self.gen_count = QSpinBox(minimum=1, maximum=100000,
                                  value=int(self.settings.value("farm2/genCount", 40)))
        self.gen_count.setToolTip("CMA-ES generations to run.")
        trow.addWidget(self.gen_count)
        self.train_bots_btn = QPushButton("▶ Train (bots)")
        self.train_bots_btn.setToolTip(
            "Headless server-side bots: starts the in-arena training loop on the\n"
            "running server, then runs the optimizer to feed it candidates. No\n"
            "rendering client needed. Watch with a spectator: /msf arena tp <n>."
        )
        self.train_bots_btn.clicked.connect(self.train_bots)
        trow.addWidget(self.train_bots_btn)
        self.train_sim_btn = QPushButton("🧪 Train (sim)")
        self.train_sim_btn.setToolTip(
            "Pure-Python voxel sim - fastest, needs no server or client. Great\n"
            "for a quick pre-tune; validate the winner in real MC."
        )
        self.train_sim_btn.clicked.connect(self.train_sim)
        trow.addWidget(self.train_sim_btn)
        self.bot_demo_btn = QPushButton("🤖 Run one bot")
        self.bot_demo_btn.setToolTip("Run a single watchable bot episode in arena 0 (/msf bot 0).")
        self.bot_demo_btn.clicked.connect(self.run_one_bot)
        trow.addWidget(self.bot_demo_btn)
        self.stop_train_btn = QPushButton("■ Stop training")
        self.stop_train_btn.clicked.connect(self.stop_training)
        trow.addWidget(self.stop_train_btn)
        self.train_status = QLabel("tuner: idle")
        trow.addWidget(self.train_status, 1)
        layout.addWidget(train)

        tnote = QLabel(
            "Bots train against the real arena world (no client, no GLFW); the "
            "winner is exported to best.json and the client loads it on next join. "
            "Boost throughput with /tick rate in the server console."
        )
        tnote.setWordWrap(True)
        tnote.setStyleSheet("color: #999;")
        layout.addWidget(tnote)

        self.log = LogView()
        layout.addWidget(self.log, 1)

        self._update_state()

    # --- build ----------------------------------------------------------------

    def _build(self) -> None:
        if self.build_proc.running:
            return
        log.info("farm2: building plugin + client")
        self.log.append_line("$ gradlew build (plugin, then client)")
        # plugin/ and client/ are independent gradle projects; build both.
        cmd = ("gradlew.bat build --console=plain && "
               "cd ..\\client && gradlew.bat build --console=plain")
        self.build_proc.start("cmd.exe", ["/c", cmd], str(PLUGIN_DIR))

    def _on_build_finished(self, code: int) -> None:
        self.log.append_line(f"[build exited with code {code}]")
        self._update_state()

    # --- server ---------------------------------------------------------------

    def start_server(self) -> None:
        if self.server_proc.running:
            return
        log.info("farm2: starting Folia/Paper server (runServer)")
        self.log.append_line("$ gradlew runServer (plugin)")
        self.server_proc.start(
            "cmd.exe", ["/c", "gradlew.bat", "runServer", "--console=plain"], str(PLUGIN_DIR)
        )
        self._update_state()

    def stop_server(self) -> None:
        self.server_proc.stop()
        # The gradle daemon owns the server JVM; kill the java process in plugin/run.
        killed = self._kill_java_under(
            lambda cwd: cwd.parent == PLUGIN_DIR and cwd.name == "run"
        )
        log.info("farm2: server stopped (%d JVM(s) terminated)", killed)
        self.log.append_line(f"[server stopped - {killed} JVM(s) terminated]")
        self._update_state()

    def _on_server_exit(self, code: int) -> None:
        self.log.append_line(f"[server exited ({code})]")
        self._update_state()

    # --- clients --------------------------------------------------------------

    def launch_clients(self) -> None:
        if self._launch_queue:
            return
        self.settings.setValue("farm2/clientCount", self.client_count.value())
        self.settings.setValue("farm2/autojoin", self.autojoin.isChecked())
        self.settings.setValue("farm2/mute", self.mute.isChecked())
        self.settings.setValue("farm2/serverAddr", self.server_addr.text().strip())
        count = self.client_count.value()
        self._launch_queue = list(range(1, count + 1))
        log.info("farm2: launching %d client(s), auto-join=%s server=%s",
                 count, self.autojoin.isChecked(), self.server_addr.text().strip())
        self.log.append_line(
            f"[launching {count} client(s), {LAUNCH_STAGGER_MS // 1000}s apart]"
        )
        self._launch_next()
        if self._launch_queue:
            self._launch_timer.start()

    def _launch_next(self) -> None:
        if not self._launch_queue:
            self._launch_timer.stop()
            return
        idx = self._launch_queue.pop(0)
        # Each client gets its own project cache (so parallel runClient calls
        # don't deadlock on the build lock), build dir, and run dir.
        args = [
            "/c", "gradlew.bat", "runClient", "--console=plain",
            "--project-cache-dir", f".gradle-client{idx}",
            f"-Pminesight.buildSuffix=client{idx}",
            f"-Pminesight.runDir=run-client{idx}",
        ]
        if self.autojoin.isChecked() and self.server_addr.text().strip():
            args.append(f"-Pminesight.server={self.server_addr.text().strip()}")
        self._prep_run_dir(CLIENT_DIR / f"run-client{idx}")
        proc = ManagedProcess(self)
        proc.line.connect(lambda ln, c=idx: self.log.append_line(f"[C{c}] {ln}"))
        proc.started.connect(self._update_running)
        proc.finished.connect(lambda code, c=idx: self._on_client_exit(c, code))
        proc.start("cmd.exe", args, str(CLIENT_DIR))
        self._client_procs.append((idx, proc))
        self.log.append_line(f"[client {idx} starting → client/run-client{idx}]")
        self._update_running()
        if not self._launch_queue:
            self._launch_timer.stop()

    def launch_test_client(self) -> None:
        """One client in the default client/run dir for testing (overlay /
        capture). Its own run dir + project cache so it coexists with the farm."""
        args = ["/c", "gradlew.bat", "runClient", "--console=plain"]
        if self.autojoin.isChecked() and self.server_addr.text().strip():
            args.append(f"-Pminesight.server={self.server_addr.text().strip()}")
        self._prep_run_dir(CLIENT_DIR / "run")
        proc = ManagedProcess(self)
        proc.line.connect(lambda ln: self.log.append_line(f"[test] {ln}"))
        proc.started.connect(self._update_running)
        proc.finished.connect(lambda code: self._on_client_exit("test", code))
        proc.start("cmd.exe", args, str(CLIENT_DIR))
        self._client_procs.append((0, proc))
        log.info("farm2: launching test client (auto-join=%s)", self.autojoin.isChecked())
        self.log.append_line("[test client starting → client/run]")
        self._update_running()

    def _on_client_exit(self, idx, code: int) -> None:
        self.log.append_line(f"[client {idx} exited ({code})]")
        self._update_running()

    def stop_clients(self) -> None:
        self._launch_queue = []
        self._launch_timer.stop()
        for _idx, proc in self._client_procs:
            proc.stop()
        killed = self._kill_java_under(
            lambda cwd: cwd.parent == CLIENT_DIR and cwd.name.startswith("run")
        )
        log.info("farm2: clients stopped (%d game process(es) terminated)", killed)
        self.log.append_line(f"[clients stopped - {killed} game process(es) terminated]")
        self._update_running()

    # --- training -------------------------------------------------------------

    def train_bots(self) -> None:
        """Headless server-side bot training: kick off the in-arena loop on the
        server, then run the optimizer to feed it candidates."""
        if self.train_proc.running:
            return
        if not self.server_proc.running:
            self.log.append_line("[start the server first, then Train (bots)]")
            return
        self._save_train_settings()
        n = self.arena_count.value()
        g = self.gen_count.value()
        # CMA-ES asks ~11 candidates/generation by default, so with more arenas
        # than that the extras would sit idle with nothing to evaluate. Size the
        # population to the arena count (min 12) so every arena stays busy.
        pop = max(n, 12)
        self.server_proc.send(f"msf train start {n}")
        self.log.append_line(f"$ msf train start {n}  (server console)")
        self.log.append_line(f"$ python -m minesight.evolve --gens {g} --popsize {pop}")
        self.train_proc.start(
            PYTHON, ["-m", "minesight.evolve", "--gens", str(g), "--popsize", str(pop)],
            str(ENGINE_DIR),
        )
        log.info("farm2: bot training started (arenas=%d gens=%d popsize=%d)", n, g, pop)
        self._update_state()

    def train_sim(self) -> None:
        """Pure-Python voxel sim - no server or client needed."""
        if self.train_proc.running:
            return
        self._save_train_settings()
        g = self.gen_count.value()
        self.log.append_line(f"$ python -m minesight.evolve --sim --gens {g}")
        self.train_proc.start(
            PYTHON, ["-m", "minesight.evolve", "--sim", "--gens", str(g)], str(ENGINE_DIR)
        )
        log.info("farm2: sim training started (gens=%d)", g)
        self._update_state()

    def run_one_bot(self) -> None:
        if not self.server_proc.running:
            self.log.append_line("[start the server first, then Run one bot]")
            return
        self.server_proc.send("msf bot 0")
        self.log.append_line("$ msf bot 0  (server console) - /msf arena tp 0 to watch")

    def stop_training(self) -> None:
        if self.server_proc.running:
            self.server_proc.send("msf train stop")
        self.train_proc.stop()
        self.log.append_line("[training stopped]")
        self._update_state()

    def _on_train_exit(self, code: int) -> None:
        self.log.append_line(f"[optimizer exited ({code})]")
        self._update_state()

    def _save_train_settings(self) -> None:
        self.settings.setValue("farm2/arenaCount", self.arena_count.value())
        self.settings.setValue("farm2/genCount", self.gen_count.value())

    # --- helpers --------------------------------------------------------------

    def _prep_run_dir(self, run_dir: Path) -> None:
        """Ensure the client's run dir exists and its master volume matches the
        Mute box (written to options.txt, which Minecraft reads on startup)."""
        run_dir.mkdir(parents=True, exist_ok=True)
        opts = run_dir / "options.txt"
        value = "0.0" if self.mute.isChecked() else "1.0"
        lines = opts.read_text(encoding="utf-8", errors="replace").splitlines() if opts.exists() else []
        for i, line in enumerate(lines):
            if line.startswith("soundCategory_master:"):
                lines[i] = f"soundCategory_master:{value}"
                break
        else:
            lines.append(f"soundCategory_master:{value}")
        opts.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def _kill_java_under(self, predicate) -> int:
        """Kill java processes whose working dir matches predicate (gradle's
        daemon owns the forked JVM, so killing gradlew alone won't stop it)."""
        killed = 0
        try:
            import psutil

            for p in psutil.process_iter(["name"]):
                try:
                    if (p.info["name"] or "").lower() not in ("java.exe", "javaw.exe"):
                        continue
                    if predicate(Path(p.cwd())):
                        p.kill()
                        killed += 1
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue
        except Exception as e:
            self.log.append_line(f"[process cleanup failed: {e}]")
        return killed

    def _on_line(self, line: str) -> None:
        self.log.append_line(line)

    def _update_running(self) -> None:
        n = sum(1 for _idx, p in self._client_procs if p.running)
        self.running_label.setText(f"{n} running")

    def _update_state(self) -> None:
        building = self.build_proc.running
        server_up = self.server_proc.running
        tuning = self.train_proc.running
        self.build_btn.setEnabled(not building)
        self.start_server_btn.setEnabled(not server_up)
        self.stop_server_btn.setEnabled(server_up)
        self.server_status.setText("server: running" if server_up else "server: stopped")
        self.train_bots_btn.setEnabled(server_up and not tuning)
        self.train_sim_btn.setEnabled(not tuning)
        self.bot_demo_btn.setEnabled(server_up)
        self.stop_train_btn.setEnabled(tuning)
        self.train_status.setText("tuner: running" if tuning else "tuner: idle")

    def shutdown(self) -> None:
        if self.server_proc.running:
            self.server_proc.send("msf train stop")
        self.train_proc.stop()
        self.stop_clients()
        self.stop_server()
        self.build_proc.stop()
