from __future__ import annotations

import time

from PySide6.QtCore import QTimer, Qt, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import (
    QHBoxLayout,
    QHeaderView,
    QPushButton,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from matplotlib.backends.backend_qtagg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure

from .runs import RunInfo, scan_runs


class ModelsTab(QWidget):
    """Table of all training runs with metric curves for the selected run."""

    weightsChosen = Signal(str)

    COLS = ["Run", "Model", "Dataset", "Epochs", "mAP50", "mAP50-95", "Updated"]

    def __init__(self, parent=None):
        super().__init__(parent)
        self._runs: list[RunInfo] = []

        layout = QVBoxLayout(self)
        split = QSplitter(Qt.Orientation.Vertical)
        layout.addWidget(split)

        self.table = QTableWidget(0, len(self.COLS))
        self.table.setHorizontalHeaderLabels(self.COLS)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.itemSelectionChanged.connect(self._plot_selected)
        split.addWidget(self.table)

        self.fig = Figure(figsize=(5, 3), tight_layout=True)
        self.canvas = FigureCanvas(self.fig)
        split.addWidget(self.canvas)
        split.setSizes([300, 320])

        btns = QHBoxLayout()
        self.use_btn = QPushButton("Use in engine")
        self.use_btn.clicked.connect(self._use_selected)
        btns.addWidget(self.use_btn)
        open_btn = QPushButton("Open run folder")
        open_btn.clicked.connect(self._open_selected)
        btns.addWidget(open_btn)
        refresh = QPushButton("⟳ Refresh")
        refresh.clicked.connect(self.refresh)
        btns.addWidget(refresh)
        btns.addStretch(1)
        layout.addLayout(btns)

        # Live-updating while a training run writes results.csv
        self._timer = QTimer(self, interval=15000)
        self._timer.timeout.connect(self.refresh)
        self._timer.start()
        self.refresh()

    def selected(self) -> RunInfo | None:
        rows = self.table.selectionModel().selectedRows()
        if not rows or rows[0].row() >= len(self._runs):
            return None
        return self._runs[rows[0].row()]

    def refresh(self) -> None:
        prev = self.selected()
        self._runs = scan_runs()
        self.table.setRowCount(len(self._runs))
        for i, r in enumerate(self._runs):
            done = f"{r.epochs_done}/{r.epochs_planned}" if r.epochs_planned else str(r.epochs_done)
            vals = [
                r.name + ("" if r.best else "  (no best.pt)"),
                r.model,
                r.data.replace("\\", "/").split("/")[-2] if "/" in r.data.replace("\\", "/") else r.data,
                done,
                f"{r.map50:.3f}" if r.map50 is not None else "-",
                f"{r.map50_95:.3f}" if r.map50_95 is not None else "-",
                time.strftime("%Y-%m-%d %H:%M", time.localtime(r.mtime)),
            ]
            for j, v in enumerate(vals):
                self.table.setItem(i, j, QTableWidgetItem(v))
        if prev:
            for i, r in enumerate(self._runs):
                if r.run_dir == prev.run_dir:
                    self.table.selectRow(i)
                    break
        elif self._runs:
            self.table.selectRow(0)

    def _plot_selected(self) -> None:
        run = self.selected()
        self.fig.clear()
        if not run or not run.history:
            self.canvas.draw_idle()
            return
        epochs = list(range(1, len(run.history) + 1))

        def series(key):
            out = []
            for row in run.history:
                try:
                    out.append(float(row[key]))
                except (KeyError, ValueError):
                    out.append(float("nan"))
            return out

        ax = self.fig.add_subplot(111)
        ax.plot(epochs, series("metrics/mAP50(B)"), label="mAP50", color="#4aedd9")
        ax.plot(epochs, series("metrics/mAP50-95(B)"), label="mAP50-95", color="#2ecc40")
        ax.set_xlabel("epoch")
        ax.set_ylabel("mAP")
        ax.set_title(run.name)
        ax2 = ax.twinx()
        ax2.plot(epochs, series("val/box_loss"), label="val box loss", color="#ff4136", linestyle="--", alpha=0.6)
        ax2.set_ylabel("val box loss")
        lines, labels = ax.get_legend_handles_labels()
        lines2, labels2 = ax2.get_legend_handles_labels()
        ax.legend(lines + lines2, labels + labels2, loc="center right", fontsize=8)
        self.canvas.draw_idle()

    def _use_selected(self) -> None:
        run = self.selected()
        if run and run.best:
            self.weightsChosen.emit(str(run.best))

    def _open_selected(self) -> None:
        run = self.selected()
        if run:
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(run.run_dir)))
