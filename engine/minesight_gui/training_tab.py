from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSettings, Signal
from PySide6.QtWidgets import (
    QComboBox,
    QFileDialog,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from .constants import ENGINE_DIR, PYTHON, STOCK_MODELS
from .health import list_datasets
from .procs import ManagedProcess
from .widgets import LogView


class TrainingTab(QWidget):
    trainingFinished = Signal(int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.proc = ManagedProcess(self)
        self.proc.line.connect(self._on_line)
        self.proc.started.connect(self._update_state)
        self.proc.finished.connect(self._on_finished)

        layout = QVBoxLayout(self)

        row1 = QHBoxLayout()
        row1.addWidget(QLabel("Dataset:"))
        self.dataset = QComboBox()
        self.dataset.setMinimumWidth(260)
        row1.addWidget(self.dataset, 1)
        refresh = QPushButton("⟳")
        refresh.setFixedWidth(32)
        refresh.clicked.connect(self.refresh_datasets)
        row1.addWidget(refresh)
        row1.addWidget(QLabel("Base model:"))
        self.model = QComboBox()
        self.model.addItems(STOCK_MODELS)
        self.model.setToolTip(
            "Pretrained weights to fine-tune:\n"
            "yolo26n = fastest, least accurate\n"
            "yolo26s = recommended for the RTX 4060 Ti\n"
            "yolo26m = most accurate, slower"
        )
        row1.addWidget(self.model)
        browse = QPushButton("Browse…")
        browse.clicked.connect(self._browse_model)
        row1.addWidget(browse)
        layout.addLayout(row1)

        row2 = QHBoxLayout()
        row2.addWidget(QLabel("Epochs:"))
        self.epochs = QSpinBox(minimum=1, maximum=1000, value=100)
        self.epochs.setToolTip("Full passes over the training set. 100 ≈ 30-60 min on the 4060 Ti.")
        row2.addWidget(self.epochs)
        row2.addWidget(QLabel("Batch size:"))
        self.batch = QSpinBox(minimum=1, maximum=128, value=16)
        self.batch.setToolTip("Images per training step. 16 is safe for 8 GB VRAM; lower it if you hit out-of-memory.")
        row2.addWidget(self.batch)
        row2.addWidget(QLabel("Image size (px):"))
        self.imgsz = QSpinBox(minimum=320, maximum=1920, singleStep=32, value=640)
        self.imgsz.setToolTip("Train at the same size you run the engine at (640 default).")
        row2.addWidget(self.imgsz)
        row2.addWidget(QLabel("Run name:"))
        self.name = QLineEdit()
        self.name.setPlaceholderText("auto: <model>_<dataset>")
        row2.addWidget(self.name, 1)
        layout.addLayout(row2)

        row3 = QHBoxLayout()
        self.status = QLabel("Idle. One training at a time - the GPU is shared with the engine.")
        row3.addWidget(self.status, 1)
        self.start_btn = QPushButton("▶ Start training")
        self.start_btn.clicked.connect(self.start_training)
        row3.addWidget(self.start_btn)
        self.stop_btn = QPushButton("■ Stop")
        self.stop_btn.setEnabled(False)
        self.stop_btn.setToolTip("Kills the run; progress up to the last finished epoch stays in last.pt")
        self.stop_btn.clicked.connect(self.proc.stop)
        row3.addWidget(self.stop_btn)
        layout.addLayout(row3)

        self.log = LogView()
        layout.addWidget(self.log, 1)

        self.refresh_datasets()
        self._load_settings()

    def _load_settings(self) -> None:
        s = QSettings("MineSight", "ControlPanel")
        self.epochs.setValue(int(s.value("training/epochs", 100)))
        self.batch.setValue(int(s.value("training/batch", 16)))
        self.imgsz.setValue(int(s.value("training/imgsz", 640)))
        model = s.value("training/model", "")
        if model:
            idx = self.model.findText(model)
            if idx >= 0:
                self.model.setCurrentIndex(idx)

    def _save_settings(self) -> None:
        s = QSettings("MineSight", "ControlPanel")
        s.setValue("training/epochs", self.epochs.value())
        s.setValue("training/batch", self.batch.value())
        s.setValue("training/imgsz", self.imgsz.value())
        s.setValue("training/model", self.model.currentText())

    def refresh_datasets(self) -> None:
        current = self.dataset.currentData()
        self.dataset.clear()
        for ds in list_datasets():
            self.dataset.addItem(ds.name, str(ds / "data.yaml"))
        if current:
            idx = self.dataset.findData(current)
            if idx >= 0:
                self.dataset.setCurrentIndex(idx)
            else:
                self.dataset.setCurrentIndex(self.dataset.count() - 1)
        else:
            self.dataset.setCurrentIndex(self.dataset.count() - 1)  # newest version last

    def _browse_model(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choose base weights", str(ENGINE_DIR), "YOLO weights (*.pt)")
        if path:
            self.model.addItem(path)
            self.model.setCurrentIndex(self.model.count() - 1)

    def start_training(self) -> None:
        if self.proc.running:
            return
        data = self.dataset.currentData()
        if not data:
            self.status.setText("No dataset found under engine/datasets/")
            return
        self._save_settings()
        model = self.model.currentText()
        name = self.name.text().strip() or f"{Path(model).stem}_{self.dataset.currentText()}"
        args = [
            "-u", "train.py",
            "--data", str(data),
            "--model", model,
            "--epochs", str(self.epochs.value()),
            "--batch", str(self.batch.value()),
            "--imgsz", str(self.imgsz.value()),
            "--name", name,
        ]
        self.log.append_line(f"$ python {' '.join(args[1:])}")
        self.proc.start(PYTHON, args, str(ENGINE_DIR))
        self.status.setText(f"Training '{name}'…")

    def _on_line(self, line: str) -> None:
        self.log.append_line(line)

    def _on_finished(self, code: int) -> None:
        self.log.append_line(f"[training exited with code {code}]")
        self.status.setText("Finished." if code == 0 else f"Stopped/failed (exit {code}).")
        self._update_state()
        self.trainingFinished.emit(code)

    def _update_state(self) -> None:
        running = self.proc.running
        self.start_btn.setEnabled(not running)
        self.stop_btn.setEnabled(running)

    def shutdown(self) -> None:
        self.proc.stop()
