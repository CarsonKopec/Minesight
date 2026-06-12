from __future__ import annotations

import json
import math
import time

from PySide6.QtCore import QSettings, Qt, Signal
from PySide6.QtNetwork import QHostAddress
from PySide6.QtWebSockets import QWebSocketServer
from PySide6.QtWidgets import (
    QCheckBox,
    QDoubleSpinBox,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSpinBox,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from . import collect_io
from .constants import DATASETS_DIR
from .gallery import ImageInspector
from .health import list_datasets
from .widgets import LogView

COLLECTOR_PORT = 8766

# Canonical order defines the label indices the mod writes.
COLLECTIBLE = [
    ("diamond_ore", True),
    ("gold_ore", True),
    ("iron_ore", True),
    ("lapis_ore", True),
    ("redstone_ore", True),
    ("coal_ore", False),
    ("emerald_ore", False),
]


class CollectorTab(QWidget):
    """Automated in-game dataset collection, orchestrating one or more game
    clients over WebSocket. Each client must run its own world; the target
    image count is split across all connected clients."""

    datasetsChanged = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._clients: dict = {}  # QWebSocket -> {id, saved, visited, done, in_session}
        self._next_client_id = 1
        self._collecting = False
        self._session_name: str | None = None
        self._session_classes: list[str] = []

        self.server = QWebSocketServer(
            "MineSight Collector", QWebSocketServer.SslMode.NonSecureMode, self
        )
        listening = self.server.listen(QHostAddress.SpecialAddress.LocalHost, COLLECTOR_PORT)
        self.server.newConnection.connect(self._on_connection)

        layout = QVBoxLayout(self)

        self.conn_status = QLabel()
        layout.addWidget(self.conn_status)

        form_box = QGroupBox("Session settings")
        grid = QGridLayout(form_box)
        grid.addWidget(QLabel("Dataset name:"), 0, 0)
        self.name = QLineEdit(time.strftime("collected-%Y%m%d-%H%M"))
        self.name.setToolTip("Images land in engine/datasets/<name>/pool until you finalize")
        grid.addWidget(self.name, 0, 1)
        grid.addWidget(QLabel("Images to collect:"), 0, 2)
        self.target = QSpinBox(minimum=10, maximum=10000, value=300)
        self.target.setToolTip("Total across all connected clients - the work is split evenly")
        grid.addWidget(self.target, 0, 3)
        grid.addWidget(QLabel("Search radius (blocks):"), 0, 4)
        self.radius = QSpinBox(minimum=50, maximum=5000, value=300, singleStep=50)
        self.radius.setToolTip("How far from the start point teleports may roam.\nThe search center also auto-moves when an area runs dry.")
        grid.addWidget(self.radius, 0, 5)

        grid.addWidget(QLabel("Depth range (Y):"), 1, 0)
        self.y_min = QSpinBox(minimum=-64, maximum=320, value=5)
        self.y_max = QSpinBox(minimum=-64, maximum=320, value=62)
        ytip = (
            "Teleport depth range. Accepts modern-MC values (-64 to 320);\n"
            "1.8.9 worlds only span 0-255, so out-of-range values are clamped\n"
            "in-game. Each hop also targets the spawn band of the ore it hunts."
        )
        self.y_min.setToolTip(ytip)
        self.y_max.setToolTip(ytip)
        yrow = QHBoxLayout()
        yrow.addWidget(self.y_min)
        yrow.addWidget(QLabel("to"))
        yrow.addWidget(self.y_max)
        grid.addLayout(yrow, 1, 1)
        grid.addWidget(QLabel("Brightness (gamma):"), 1, 2)
        self.gamma_min = QDoubleSpinBox(minimum=0.0, maximum=10.0, value=0.0, singleStep=0.1)
        self.gamma_max = QDoubleSpinBox(minimum=0.0, maximum=10.0, value=1.5, singleStep=0.1)
        gtip = "Each shot uses a random brightness in this range -\nvaried cave lighting makes the model robust."
        self.gamma_min.setToolTip(gtip)
        self.gamma_max.setToolTip(gtip)
        grow = QHBoxLayout()
        grow.addWidget(self.gamma_min)
        grow.addWidget(QLabel("to"))
        grow.addWidget(self.gamma_max)
        grid.addLayout(grow, 1, 3)
        grid.addWidget(QLabel("Field of view:"), 1, 4)
        self.fov_min = QSpinBox(minimum=30, maximum=110, value=70)
        self.fov_max = QSpinBox(minimum=30, maximum=110, value=110)
        ftip = "Each shot uses a random FOV in this range,\nmatching however you actually play."
        self.fov_min.setToolTip(ftip)
        self.fov_max.setToolTip(ftip)
        frow = QHBoxLayout()
        frow.addWidget(self.fov_min)
        frow.addWidget(QLabel("to"))
        frow.addWidget(self.fov_max)
        grid.addLayout(frow, 1, 5)

        grid.addWidget(QLabel("Background frames:"), 2, 0)
        self.negatives = QDoubleSpinBox(minimum=0.0, maximum=0.5, value=0.0, singleStep=0.02)
        self.negatives.setToolTip(
            "0 = only save frames that contain ore boxes (default).\n"
            "Raising this saves some background-only frames with empty labels,\n"
            "which can reduce false positives - but the Roboflow data already\n"
            "includes ~550 background images, so it's rarely needed."
        )
        grid.addWidget(self.negatives, 2, 1)
        grid.addWidget(QLabel("Render wait (ticks):"), 2, 2)
        self.settle = QSpinBox(minimum=10, maximum=200, value=40)
        self.settle.setToolTip(
            "Wait after each teleport so chunks actually render before capturing.\n"
            "Raise this if you still see void/black frames; the mod also auto-retries\n"
            "frames that come back black or featureless."
        )
        grid.addWidget(self.settle, 2, 3)
        classes_label = QLabel("Classes (+ box goals):")
        classes_label.setToolTip(
            "Tick the ores to photograph. The number next to each is an optional\n"
            "GOAL in boxes (0 = none): if any goal is set, the session keeps going\n"
            "until every goal is met (or the image cap above is reached), and the\n"
            "collector actively hunts whichever class is furthest behind."
        )
        grid.addWidget(classes_label, 3, 0)
        classes_row = QHBoxLayout()
        self.class_checks: list[QCheckBox] = []
        self.class_goals: dict[str, QSpinBox] = {}
        for label, default in COLLECTIBLE:
            cb = QCheckBox(label.replace("_ore", ""))
            cb.setChecked(default)
            cb.setProperty("label", label)
            self.class_checks.append(cb)
            classes_row.addWidget(cb)
            goal = QSpinBox(minimum=0, maximum=5000, value=0)
            goal.setFixedWidth(64)
            goal.setToolTip(f"Minimum {label} boxes to collect (0 = no goal)")
            self.class_goals[label] = goal
            classes_row.addWidget(goal)
            classes_row.addSpacing(8)
        classes_row.addStretch(1)
        grid.addLayout(classes_row, 3, 1, 1, 5)
        layout.addWidget(form_box)

        controls = QHBoxLayout()
        self.start_btn = QPushButton("▶ Start collecting")
        self.start_btn.clicked.connect(self.start_collection)
        controls.addWidget(self.start_btn)
        self.stop_btn = QPushButton("■ Stop")
        self.stop_btn.setEnabled(False)
        self.stop_btn.clicked.connect(self.stop_collection)
        controls.addWidget(self.stop_btn)
        self.skip_visited = QCheckBox("Skip visited ores")
        self.skip_visited.setChecked(True)
        self.skip_visited.setToolTip(
            "Remember every captured ore per world (survives restarts) and never\n"
            "photograph the same vein twice - avoids near-duplicate images."
        )
        controls.addWidget(self.skip_visited)
        clear_hist = QPushButton("Clear world history")
        clear_hist.setToolTip("Forget which ores were captured (sent to every connected client)")
        clear_hist.clicked.connect(self._clear_history)
        controls.addWidget(clear_hist)
        save_btn = QPushButton("💾 Save settings")
        save_btn.setToolTip("Remember every value on this tab for future sessions")
        save_btn.clicked.connect(self._save_settings)
        controls.addWidget(save_btn)
        self.apply_btn = QPushButton("⚡ Apply live")
        self.apply_btn.setToolTip(
            "Push the current settings into the RUNNING session - takes effect\n"
            "from the next shot (classes and dataset name stay fixed)."
        )
        self.apply_btn.setEnabled(False)
        self.apply_btn.clicked.connect(self._apply_live)
        controls.addWidget(self.apply_btn)
        controls.addStretch(1)
        self.finalize_btn = QPushButton("📦 Finalize dataset…")
        self.finalize_btn.setToolTip("Split a collected pool 80/10/10, write data.yaml, optionally merge into another dataset")
        self.finalize_btn.clicked.connect(self.finalize)
        controls.addWidget(self.finalize_btn)
        layout.addLayout(controls)

        progress_row = QHBoxLayout()
        self.progress = QProgressBar()
        progress_row.addWidget(self.progress, 1)
        self.progress_label = QLabel("0 / 0")
        progress_row.addWidget(self.progress_label)
        layout.addLayout(progress_row)

        body = QSplitter(Qt.Orientation.Vertical)
        self.inspector = ImageInspector()
        body.addWidget(self.inspector)
        self.log = LogView()
        body.addWidget(self.log)
        body.setSizes([450, 130])
        layout.addWidget(body, 1)

        if listening:
            self._update_conn_status()
        else:
            self.conn_status.setText(
                f"❌ Could not listen on port {COLLECTOR_PORT} - is another Control Panel open?"
            )
            self.start_btn.setEnabled(False)

        self._load_settings()

    # --- settings persistence --------------------------------------------------

    _SPINS = ("target", "radius", "y_min", "y_max", "gamma_min", "gamma_max",
              "fov_min", "fov_max", "negatives", "settle")

    def _save_settings(self) -> None:
        s = QSettings("MineSight", "ControlPanel")
        for key in self._SPINS:
            s.setValue(f"collector/{key}", getattr(self, key).value())
        s.setValue("collector/skipVisited", self.skip_visited.isChecked())
        s.setValue("collector/classes", ",".join(self._checked_classes()))
        s.setValue(
            "collector/classGoals",
            ",".join(f"{label}:{spin.value()}" for label, spin in self.class_goals.items()),
        )
        self.log.append_line("[settings saved]")

    def _load_settings(self) -> None:
        s = QSettings("MineSight", "ControlPanel")
        for key in self._SPINS:
            value = s.value(f"collector/{key}")
            if value is not None:
                widget = getattr(self, key)
                widget.setValue(type(widget.value())(value))
        skip = s.value("collector/skipVisited")
        if skip is not None:
            self.skip_visited.setChecked(s.value("collector/skipVisited", True, type=bool))
        saved_classes = s.value("collector/classes")
        if saved_classes is not None:
            chosen = set(str(saved_classes).split(","))
            for cb in self.class_checks:
                cb.setChecked(cb.property("label") in chosen)
        saved_goals = s.value("collector/classGoals")
        if saved_goals:
            for part in str(saved_goals).split(","):
                if ":" in part:
                    label, _, value = part.partition(":")
                    if label in self.class_goals and value.isdigit():
                        self.class_goals[label].setValue(int(value))

    def _apply_live(self) -> None:
        self._save_settings()
        if not self._collecting:
            return
        in_session = [(sock, info) for sock, info in self._clients.items()
                      if info["in_session"] and not info["done"]]
        if not in_session:
            return
        per_client = math.ceil(self.target.value() / len(in_session))
        update = {
            "type": "collect_update",
            "target": per_client,
            "radius": self.radius.value(),
            "y_min": self.y_min.value(),
            "y_max": self.y_max.value(),
            "gamma_min": self.gamma_min.value(),
            "gamma_max": self.gamma_max.value(),
            "fov_min": self.fov_min.value(),
            "fov_max": self.fov_max.value(),
            "negative_ratio": self.negatives.value(),
            "settle_ticks": self.settle.value(),
            "avoid_revisits": self.skip_visited.isChecked(),
            "class_targets": self._class_targets(len(in_session)),
        }
        for sock, _info in in_session:
            sock.sendTextMessage(json.dumps(update))
        self.progress.setMaximum(per_client * len(in_session))
        self._update_progress()
        self.log.append_line(f"[applied live to {len(in_session)} client(s)]")

    # --- connection handling -------------------------------------------------

    def _update_conn_status(self) -> None:
        n = len(self._clients)
        if n == 0:
            self.conn_status.setText(
                "🔌 No game clients connected - launch Minecraft 1.8.9 with MineSight ≥0.4.0 "
                "and open a singleplayer world (one world per client). They connect automatically."
            )
        else:
            ids = ", ".join(f"#{info['id']}" for info in self._clients.values())
            self.conn_status.setText(
                f"✅ {n} game client(s) connected ({ids}). The image target is split across all of them."
            )
        self._update_buttons()

    def _on_connection(self) -> None:
        sock = self.server.nextPendingConnection()
        info = {"id": self._next_client_id, "saved": 0, "visited": 0, "done": False, "in_session": False}
        self._next_client_id += 1
        self._clients[sock] = info
        sock.textMessageReceived.connect(lambda msg, s=sock: self._on_message(s, msg))
        sock.disconnected.connect(lambda s=sock: self._on_disconnect(s))
        self.log.append_line(f"[client #{info['id']} connected]")
        self._update_conn_status()

    def _on_disconnect(self, sock) -> None:
        info = self._clients.pop(sock, None)
        if info is None:
            return
        self.log.append_line(f"[client #{info['id']} disconnected]")
        if self._collecting and info["in_session"] and not info["done"]:
            self.log.append_line(f"[client #{info['id']} left mid-session - its share is lost]")
            self._check_all_done()
        self._update_conn_status()

    # --- session control -----------------------------------------------------

    def _checked_classes(self) -> list[str]:
        return [cb.property("label") for cb in self.class_checks if cb.isChecked()]

    def _class_targets(self, n_clients: int) -> dict[str, int]:
        """Per-client share of each class goal (0-goals omitted)."""
        checked = set(self._checked_classes())
        return {
            label: math.ceil(spin.value() / n_clients)
            for label, spin in self.class_goals.items()
            if label in checked and spin.value() > 0
        }

    def start_collection(self) -> None:
        if not self._clients or self._collecting:
            return
        name = self.name.text().strip() or time.strftime("collected-%Y%m%d-%H%M")
        classes = self._checked_classes()
        if not classes:
            QMessageBox.warning(self, "MineSight", "Pick at least one ore class.")
            return
        pool = collect_io.pool_dir(name)
        pool.mkdir(parents=True, exist_ok=True)
        # Record the class order so the inspector (and finalize) can name boxes.
        (DATASETS_DIR / name / "classes.json").write_text(json.dumps({"classes": classes}))
        self._session_name = name
        self._session_classes = classes

        self._save_settings()
        clients = list(self._clients.items())
        per_client = math.ceil(self.target.value() / len(clients))
        total = 0
        for sock, info in clients:
            info.update(saved=0, visited=0, done=False, in_session=True, class_boxes={})
            msg = {
                "type": "collect_start",
                "output_dir": str(pool),
                "target": per_client,
                "radius": self.radius.value(),
                "y_min": self.y_min.value(),
                "y_max": self.y_max.value(),
                "gamma_min": self.gamma_min.value(),
                "gamma_max": self.gamma_max.value(),
                "fov_min": self.fov_min.value(),
                "fov_max": self.fov_max.value(),
                "negative_ratio": self.negatives.value(),
                "settle_ticks": self.settle.value(),
                "avoid_revisits": self.skip_visited.isChecked(),
                "class_targets": self._class_targets(len(clients)),
                "classes": classes,
            }
            sock.sendTextMessage(json.dumps(msg))
            total += per_client
        self._collecting = True
        self._session_started = time.monotonic()
        self.progress.setMaximum(total)
        self.progress.setValue(0)
        self._update_buttons()
        self.log.append_line(
            f"[start: {name}, {total} images across {len(clients)} client(s) "
            f"({per_client} each), classes={classes}]"
        )

    def stop_collection(self) -> None:
        for sock, info in self._clients.items():
            if info["in_session"] and not info["done"]:
                sock.sendTextMessage(json.dumps({"type": "collect_stop"}))

    def _clear_history(self) -> None:
        if not self._clients:
            QMessageBox.information(self, "MineSight", "No game client is connected.")
            return
        for sock in self._clients:
            sock.sendTextMessage(json.dumps({"type": "collect_clear_history"}))

    def _on_message(self, sock, message: str) -> None:
        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            return
        info = self._clients.get(sock)
        if info is None:
            return
        cid = info["id"]
        msg_type = data.get("type")
        if msg_type == "collector_hello":
            self.log.append_line(f"[client #{cid} ready in-game]")
        elif msg_type == "collect_progress":
            info["saved"] = data.get("saved", info["saved"])
            info["visited"] = data.get("visited", info["visited"])
            info["class_boxes"] = data.get("class_boxes", info.get("class_boxes", {}))
            self._update_progress()
            if self._session_name and data.get("file"):
                self.inspector.on_live_capture(self._session_name, data["file"])
            self.log.append_line(f"#{cid} saved {data.get('file')} ({data.get('boxes')} boxes)")
        elif msg_type == "collect_log":
            self.log.append_line(f"[#{cid}] {data.get('message')}")
        elif msg_type == "collect_done":
            info["done"] = True
            reason = data.get("reason", "?")
            extra = f" - {data['message']}" if data.get("message") else ""
            counts = data.get("class_counts") or {}
            breakdown = ", ".join(f"{k}×{v}" for k, v in sorted(counts.items(), key=lambda i: -i[1]))
            self.log.append_line(f"[client #{cid} done: {data.get('saved', 0)} images ({reason}{extra})]")
            if breakdown:
                self.log.append_line(f"[client #{cid} class balance: {breakdown}]")
            self._check_all_done()

    def _update_progress(self) -> None:
        in_session = [i for i in self._clients.values() if i["in_session"]]
        saved = sum(i["saved"] for i in in_session)
        visited = sum(i["visited"] for i in in_session)
        self.progress.setValue(saved)
        parts = [f"{saved} / {self.progress.maximum()}"]
        minutes = (time.monotonic() - getattr(self, "_session_started", time.monotonic())) / 60
        if saved and minutes > 0.05:
            parts.append(f"{saved / minutes:.1f} img/min")
        # Per-class goal fill, totaled across clients
        goal_bits = []
        for label, spin in self.class_goals.items():
            if spin.value() > 0:
                total_boxes = sum(i.get("class_boxes", {}).get(label, 0) for i in in_session)
                goal_bits.append(f"{label.replace('_ore', '')} {total_boxes}/{spin.value()}")
        if goal_bits:
            parts.append("  ".join(goal_bits))
        if visited:
            parts.append(f"{visited} ores in history")
        self.progress_label.setText("   ·   ".join(parts))

    def _check_all_done(self) -> None:
        active = [i for i in self._clients.values() if i["in_session"] and not i["done"]]
        if self._collecting and not active:
            self._collecting = False
            total = sum(i["saved"] for i in self._clients.values() if i["in_session"])
            self.log.append_line(f"[session complete: {total} images saved]")
            if total > 0:
                self.log.append_line("→ Click 'Finalize dataset…' to split it and make it trainable.")
            self._update_buttons()

    def _update_buttons(self) -> None:
        self.start_btn.setEnabled(bool(self._clients) and not self._collecting)
        self.stop_btn.setEnabled(self._collecting)
        self.apply_btn.setEnabled(self._collecting)

    # --- finalize -------------------------------------------------------------

    def finalize(self) -> None:
        # Offer any dataset directory that still has a pool.
        pools = [p.parent.name for p in DATASETS_DIR.glob("*/pool") if any((p / "images").glob("*.png"))]
        if not pools:
            QMessageBox.information(self, "MineSight", "No collected pools waiting to be finalized.")
            return
        name, ok = QInputDialog.getItem(self, "Finalize dataset", "Collected session:", pools, 0, False)
        if not ok:
            return
        classes = self._session_classes if name == self._session_name else self._checked_classes()

        targets = ["(none - keep standalone)"] + [d.name for d in list_datasets() if d.name != name]
        merge, ok = QInputDialog.getItem(
            self, "Finalize dataset", "Also merge into:", targets, 0, False
        )
        if not ok:
            return
        try:
            ds_dir, removed = collect_io.finalize(name, classes)
            self.log.append_line(
                f"[finalized {ds_dir.name}: 80/10/10 split + data.yaml"
                + (f", {removed} near-duplicate(s) removed" if removed else "")
                + "]"
            )
            if merge != targets[0]:
                copied = collect_io.merge_into(ds_dir, DATASETS_DIR / merge)
                self.log.append_line(f"[merged {copied} images into {merge}]")
        except Exception as e:
            QMessageBox.warning(self, "MineSight", str(e))
            return
        self.inspector.refresh_sources(select=name)
        self.datasetsChanged.emit()

    def shutdown(self) -> None:
        if self._collecting:
            self.stop_collection()
        self.server.close()
