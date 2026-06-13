from __future__ import annotations

import base64
import json

from PySide6.QtCore import QSettings, QTimer, QUrl, Qt, Signal
from PySide6.QtGui import QImage, QPixmap
from PySide6.QtWebSockets import QWebSocket
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDoubleSpinBox,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .constants import ENGINE_DIR, PYTHON, STOCK_MODELS, WS_URL
from .procs import ManagedProcess
from .runs import scan_runs
from .widgets import LogView


class EngineTab(QWidget):
    """Start/stop the detection engine, watch its logs and live preview."""

    statsUpdated = Signal(dict)  # consumed by the main-window status bar
    reviewCaptured = Signal()    # a frame was flagged for review

    def __init__(self, parent=None):
        super().__init__(parent)
        self.proc = ManagedProcess(self)
        self.proc.line.connect(self._on_line)
        self.proc.started.connect(self._update_state)
        self.proc.finished.connect(self._on_finished)

        self.ws = QWebSocket()
        self.ws.connected.connect(self._ws_connected)
        self.ws.disconnected.connect(self._update_state)
        self.ws.textMessageReceived.connect(self._ws_message)
        self._ws_retry = QTimer(self, interval=3000)
        self._ws_retry.timeout.connect(self._ensure_ws)

        layout = QVBoxLayout(self)

        row1 = QHBoxLayout()
        row1.addWidget(QLabel("Weights:"))
        self.weights = QComboBox()
        self.weights.setMinimumWidth(420)
        row1.addWidget(self.weights, 1)
        browse = QPushButton("Browse…")
        browse.clicked.connect(self._browse_weights)
        row1.addWidget(browse)
        refresh = QPushButton("⟳")
        refresh.setFixedWidth(32)
        refresh.setToolTip("Rescan trained runs")
        refresh.clicked.connect(self.refresh_weights)
        row1.addWidget(refresh)
        layout.addLayout(row1)

        row2 = QHBoxLayout()
        row2.addWidget(QLabel("Inference size (px):"))
        self.imgsz = QSpinBox(minimum=320, maximum=1920, singleStep=32, value=640)
        self.imgsz.setToolTip("Resolution frames are scaled to before detection.\n640 = fast, 1280 = better at small/distant ores.")
        row2.addWidget(self.imgsz)
        row2.addWidget(QLabel("Min confidence:"))
        self.conf = QDoubleSpinBox(minimum=0.05, maximum=0.95, singleStep=0.05, value=0.40)
        self.conf.setToolTip("Detections below this score are discarded.\nRaise it if you see false boxes; lower it if real ores are missed.")
        row2.addWidget(self.conf)
        row2.addWidget(QLabel("Run on:"))
        self.device = QComboBox()
        self.device.addItem("Auto — GPU if available", "auto")
        self.device.addItem("GPU 0 (RTX 4060 Ti)", "0")
        self.device.addItem("CPU only (slow)", "cpu")
        row2.addWidget(self.device)
        self.tracking = QCheckBox("Stable IDs")
        self.tracking.setChecked(True)
        self.tracking.setToolTip(
            "Track objects across frames (ByteTrack) so each ore keeps one ID -\n"
            "steadier, flicker-free overlays. Phase 2 of the spec."
        )
        row2.addWidget(self.tracking)
        self.fp16 = QCheckBox("FP16")
        self.fp16.setChecked(True)
        self.fp16.setToolTip("Half-precision inference: ~1.5-2x faster on RTX GPUs, same accuracy.")
        row2.addWidget(self.fp16)
        row2.addStretch(1)
        self.start_btn = QPushButton("▶ Start engine")
        self.start_btn.clicked.connect(self.start_engine)
        row2.addWidget(self.start_btn)
        self.stop_btn = QPushButton("■ Stop")
        self.stop_btn.clicked.connect(self.stop_engine)
        self.stop_btn.setEnabled(False)
        row2.addWidget(self.stop_btn)
        layout.addLayout(row2)

        row3 = QHBoxLayout()
        self.status = QLabel("Engine: stopped")
        row3.addWidget(self.status)
        row3.addStretch(1)
        self.auto_review = QCheckBox("Auto-flag uncertain")
        self.auto_review.setToolTip(
            "Auto-save frames where the model is unsure (confidence 0.30-0.55)\n"
            "to the Review tab - hands-off active learning."
        )
        row3.addWidget(self.auto_review)
        self.review_btn = QPushButton("🚩 Capture for review")
        self.review_btn.setToolTip(
            "Snapshot the current frame + the model's boxes for correction in the\n"
            "Review tab. Same as pressing F9 in-game. Use it when you spot a mistake."
        )
        self.review_btn.setEnabled(False)
        self.review_btn.clicked.connect(self._capture_review)
        row3.addWidget(self.review_btn)
        self.preview_check = QCheckBox("Live preview")
        self.preview_check.setChecked(True)
        row3.addWidget(self.preview_check)
        layout.addLayout(row3)

        self.preview = QLabel("Preview appears here while the engine is running")
        self.preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview.setMinimumHeight(280)
        self.preview.setStyleSheet("background:#1a1a1a; color:#888; border:1px solid #333;")
        layout.addWidget(self.preview, 2)

        self.log = LogView()
        layout.addWidget(self.log, 1)

        self.refresh_weights()
        self._load_settings()

    # --- settings persistence ------------------------------------------------

    def _load_settings(self) -> None:
        s = QSettings("MineSight", "ControlPanel")
        self.imgsz.setValue(int(s.value("engine/imgsz", 640)))
        self.conf.setValue(float(s.value("engine/conf", 0.40)))
        idx = self.device.findData(s.value("engine/device", "auto"))
        if idx >= 0:
            self.device.setCurrentIndex(idx)
        self.tracking.setChecked(s.value("engine/track", True, type=bool))
        self.fp16.setChecked(s.value("engine/half", True, type=bool))
        self.auto_review.setChecked(s.value("engine/autoReview", False, type=bool))
        saved_weights = s.value("engine/weights", "")
        if saved_weights and self.weights.findData(saved_weights) >= 0:
            self.set_weights(saved_weights)

    def _save_settings(self) -> None:
        s = QSettings("MineSight", "ControlPanel")
        s.setValue("engine/imgsz", self.imgsz.value())
        s.setValue("engine/conf", self.conf.value())
        s.setValue("engine/device", self.device.currentData())
        s.setValue("engine/weights", self.weights.currentData() or "")
        s.setValue("engine/track", self.tracking.isChecked())
        s.setValue("engine/half", self.fp16.isChecked())
        s.setValue("engine/autoReview", self.auto_review.isChecked())

    # --- weights selection -------------------------------------------------

    def refresh_weights(self) -> None:
        current = self.weights.currentData()
        self.weights.clear()
        for r in scan_runs():
            if r.best:
                label = f"{r.name}  (mAP50 {r.map50:.3f})" if r.map50 is not None else r.name
                self.weights.addItem(label, str(r.best))
        for m in STOCK_MODELS:
            self.weights.addItem(f"{m}  (stock COCO - plumbing test only)", m)
        if current:
            self.set_weights(str(current))

    def set_weights(self, path: str) -> None:
        idx = self.weights.findData(path)
        if idx < 0:
            self.weights.addItem(path, path)
            idx = self.weights.count() - 1
        self.weights.setCurrentIndex(idx)

    def _browse_weights(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choose weights", str(ENGINE_DIR), "YOLO weights (*.pt)")
        if path:
            self.set_weights(path)

    # --- engine process ----------------------------------------------------

    def start_engine(self) -> None:
        if self.proc.running:
            return
        weights = self.weights.currentData() or "yolo26s.pt"
        self._save_settings()
        args = [
            "-u", "-m", "minesight",
            "--weights", str(weights),
            "--imgsz", str(self.imgsz.value()),
            "--conf", str(self.conf.value()),
        ]
        if self.device.currentData() != "auto":
            args += ["--device", str(self.device.currentData())]
        if not self.tracking.isChecked():
            args.append("--no-track")
        if self.fp16.isChecked() and self.device.currentData() != "cpu":
            args.append("--half")
        if self.auto_review.isChecked():
            args.append("--auto-review")
        self.log.append_line(f"$ python {' '.join(args[1:])}")
        self.proc.start(PYTHON, args, str(ENGINE_DIR))
        self._ws_retry.start()

    def stop_engine(self) -> None:
        self._ws_retry.stop()
        self.ws.close()
        self.proc.stop()

    def _on_line(self, line: str) -> None:
        self.log.append_line(line)

    def _on_finished(self, code: int) -> None:
        self.log.append_line(f"[engine exited with code {code}]")
        self._ws_retry.stop()
        self.ws.close()
        self._update_state()

    # --- websocket preview/stats --------------------------------------------

    def _ensure_ws(self) -> None:
        if self.proc.running and not self.ws.isValid():
            self.ws.open(QUrl(WS_URL))

    def _ws_connected(self) -> None:
        self.ws.sendTextMessage(json.dumps({"type": "subscribe_preview"}))
        self._update_state()

    def _capture_review(self) -> None:
        if self.ws.isValid():
            self.ws.sendTextMessage(json.dumps({"type": "review_capture"}))
            self.log.append_line("[flagged current frame for review]")
            # Give the engine a beat to write the files, then refresh the tab.
            QTimer.singleShot(400, self.reviewCaptured.emit)

    def _ws_message(self, message: str) -> None:
        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            return
        msg_type = data.get("type")
        if msg_type == "preview" and self.preview_check.isChecked():
            img = QImage.fromData(base64.b64decode(data["jpg"]), "JPEG")
            pix = QPixmap.fromImage(img).scaled(
                self.preview.size(),
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
            self.preview.setPixmap(pix)
        elif msg_type == "stats":
            window = "window OK" if data.get("window_found") else "window NOT found"
            self.status.setText(
                f"Engine: running | {data.get('fps', 0)} FPS | "
                f"{data.get('objects', 0)} objects | "
                f"{data.get('mod_clients', 0)} mod client(s) | {window}"
            )
            self.statsUpdated.emit(data)

    def _update_state(self) -> None:
        running = self.proc.running
        self.start_btn.setEnabled(not running)
        self.stop_btn.setEnabled(running)
        self.review_btn.setEnabled(running and self.ws.isValid())
        if not running:
            self.status.setText("Engine: stopped")
            self.preview.setPixmap(QPixmap())
            self.preview.setText("Preview appears here while the engine is running")
            self.statsUpdated.emit({})

    def shutdown(self) -> None:
        self.stop_engine()
