from __future__ import annotations

import sys

from PySide6.QtCore import QTimer, Qt
from PySide6.QtGui import QColor, QPalette
from PySide6.QtWidgets import QApplication, QLabel, QMainWindow, QTabWidget

from .clients_tab import ClientsTab
from .collector_tab import CollectorTab
from .datasets_tab import DatasetsTab
from .engine_tab import EngineTab
from .mod_tab import ModTab
from .models_tab import ModelsTab
from .review_tab import ReviewTab
from .training_tab import TrainingTab


def _dark_palette() -> QPalette:
    p = QPalette()
    bg, panel, text = QColor(30, 31, 34), QColor(43, 45, 48), QColor(220, 220, 220)
    accent = QColor(74, 237, 217)
    p.setColor(QPalette.ColorRole.Window, bg)
    p.setColor(QPalette.ColorRole.WindowText, text)
    p.setColor(QPalette.ColorRole.Base, panel)
    p.setColor(QPalette.ColorRole.AlternateBase, bg)
    p.setColor(QPalette.ColorRole.Text, text)
    p.setColor(QPalette.ColorRole.Button, panel)
    p.setColor(QPalette.ColorRole.ButtonText, text)
    p.setColor(QPalette.ColorRole.Highlight, accent)
    p.setColor(QPalette.ColorRole.HighlightedText, QColor(20, 20, 20))
    p.setColor(QPalette.ColorRole.ToolTipBase, panel)
    p.setColor(QPalette.ColorRole.ToolTipText, text)
    p.setColor(QPalette.ColorRole.PlaceholderText, QColor(140, 140, 140))
    return p


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("MineSight Control Panel")
        self.resize(1200, 820)

        tabs = QTabWidget()
        self.engine_tab = EngineTab()
        self.training_tab = TrainingTab()
        self.models_tab = ModelsTab()
        self.datasets_tab = DatasetsTab()
        self.collector_tab = CollectorTab()
        self.review_tab = ReviewTab()
        self.clients_tab = ClientsTab()
        self.mod_tab = ModTab()
        tabs.addTab(self.engine_tab, "🔭 Engine")
        tabs.addTab(self.models_tab, "🧠 Models")
        tabs.addTab(self.training_tab, "🏋 Training")
        tabs.addTab(self.datasets_tab, "🗂 Datasets")
        tabs.addTab(self.collector_tab, "📷 Collector")
        tabs.addTab(self.review_tab, "🔍 Review")
        tabs.addTab(self.clients_tab, "🎮 Clients")
        tabs.addTab(self.mod_tab, "🧩 Mod")
        self.setCentralWidget(tabs)
        self._tabs = tabs

        # Cross-wiring
        self.models_tab.weightsChosen.connect(self._use_weights)
        self.training_tab.trainingFinished.connect(lambda _code: self.models_tab.refresh())
        self.training_tab.trainingFinished.connect(lambda _code: self.engine_tab.refresh_weights())
        self.collector_tab.datasetsChanged.connect(self.datasets_tab.refresh)
        self.collector_tab.datasetsChanged.connect(self.training_tab.refresh_datasets)
        self.collector_tab.datasetsChanged.connect(self.review_tab.refresh_targets)
        self.review_tab.datasetsChanged.connect(self.datasets_tab.refresh)
        self.engine_tab.reviewCaptured.connect(self.review_tab.refresh)

        # Status bar: engine stats + GPU
        self.engine_status = QLabel("engine: stopped")
        self.gpu_status = QLabel("GPU: -")
        self.statusBar().addWidget(self.engine_status, 1)
        self.statusBar().addPermanentWidget(self.gpu_status)
        self.engine_tab.statsUpdated.connect(self._on_stats)

        self._nvml = None
        self._init_nvml()
        timer = QTimer(self, interval=2000)
        timer.timeout.connect(self._poll_gpu)
        timer.start()

    def _use_weights(self, path: str) -> None:
        self.engine_tab.set_weights(path)
        self._tabs.setCurrentWidget(self.engine_tab)

    def _on_stats(self, stats: dict) -> None:
        if stats:
            self.engine_status.setText(
                f"engine: {stats.get('fps', 0)} FPS | mod clients: {stats.get('mod_clients', 0)}"
            )
        else:
            self.engine_status.setText("engine: stopped")

    def _init_nvml(self) -> None:
        try:
            import pynvml

            pynvml.nvmlInit()
            self._nvml = pynvml.nvmlDeviceGetHandleByIndex(0)
            self._pynvml = pynvml
        except Exception:
            self._nvml = None

    def _poll_gpu(self) -> None:
        if self._nvml is None:
            self.gpu_status.setText("GPU: n/a")
            return
        try:
            util = self._pynvml.nvmlDeviceGetUtilizationRates(self._nvml).gpu
            mem = self._pynvml.nvmlDeviceGetMemoryInfo(self._nvml)
            temp = self._pynvml.nvmlDeviceGetTemperature(
                self._nvml, self._pynvml.NVML_TEMPERATURE_GPU
            )
            self.gpu_status.setText(
                f"GPU: {util}% | VRAM {mem.used / 1e9:.1f}/{mem.total / 1e9:.1f} GB | {temp}°C"
            )
        except Exception:
            self.gpu_status.setText("GPU: n/a")

    def shutdown_all(self) -> None:
        # Idempotent: runs on window close AND app quit (quit() skips closeEvent).
        for tab in (self.engine_tab, self.training_tab, self.mod_tab, self.collector_tab,
                    self.clients_tab, self.review_tab):
            tab.shutdown()

    def closeEvent(self, event) -> None:
        self.shutdown_all()
        super().closeEvent(event)


def main() -> int:
    app = QApplication(sys.argv)
    app.setStyle("Fusion")
    app.setPalette(_dark_palette())
    win = MainWindow()
    app.aboutToQuit.connect(win.shutdown_all)
    win.show()
    return app.exec()
