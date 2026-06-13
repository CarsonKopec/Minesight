from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QListWidget,
    QMessageBox,
    QPushButton,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from matplotlib.backends.backend_qtagg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure

from . import collect_io
from .constants import DATASETS_DIR
from .health import DatasetHealth, SPLITS, analyze, list_datasets


class DatasetsTab(QWidget):
    """Dataset browser with class balance and labeling-bug warnings."""

    datasetsChanged = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        layout = QVBoxLayout(self)

        split = QSplitter(Qt.Orientation.Horizontal)
        layout.addWidget(split, 1)

        left = QWidget()
        lv = QVBoxLayout(left)
        lv.setContentsMargins(0, 0, 0, 0)
        self.listw = QListWidget()
        self.listw.currentTextChanged.connect(self._show_dataset)
        lv.addWidget(self.listw)
        self.rebalance_btn = QPushButton("⚖ Rebalance splits")
        self.rebalance_btn.setToolTip(
            "Pool every image in this dataset and re-split 80/10/10, stratified so\n"
            "each class (and background) is spread proportionally across\n"
            "train/valid/test. Fixes drift from appended data and rare classes\n"
            "that ended up with too few validation samples."
        )
        self.rebalance_btn.clicked.connect(self._rebalance)
        lv.addWidget(self.rebalance_btn)
        refresh = QPushButton("⟳ Refresh")
        refresh.clicked.connect(self.refresh)
        lv.addWidget(refresh)
        split.addWidget(left)

        right = QWidget()
        rv = QVBoxLayout(right)
        rv.setContentsMargins(0, 0, 0, 0)
        self.warnings = QLabel()
        self.warnings.setWordWrap(True)
        self.warnings.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.warnings.setStyleSheet("color:#ffb347;")
        rv.addWidget(self.warnings)
        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(["Split", "Images", "Label files", "Empty", "Boxes"])
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.setMaximumHeight(150)
        rv.addWidget(self.table)
        self.fig = Figure(figsize=(6, 3), tight_layout=True)
        self.canvas = FigureCanvas(self.fig)
        rv.addWidget(self.canvas, 1)
        split.addWidget(right)
        split.setSizes([220, 760])

        self.refresh()

    def refresh(self) -> None:
        current = self.listw.currentItem().text() if self.listw.currentItem() else None
        self.listw.clear()
        for ds in list_datasets():
            self.listw.addItem(ds.name)
        if current:
            matches = self.listw.findItems(current, Qt.MatchFlag.MatchExactly)
            if matches:
                self.listw.setCurrentItem(matches[0])
                return
        if self.listw.count():
            self.listw.setCurrentRow(self.listw.count() - 1)

    def _show_dataset(self, name: str) -> None:
        if not name:
            return
        for ds in list_datasets():
            if ds.name == name:
                self._render(analyze(ds))
                return

    def _rebalance(self) -> None:
        item = self.listw.currentItem()
        if item is None:
            return
        name = item.text()
        ds_dir = DATASETS_DIR / name
        reply = QMessageBox.question(
            self, "Rebalance splits",
            f"Re-split every image in '{name}' into a fresh 80/10/10 "
            "train/valid/test, stratified by class?\n\n"
            "Files are moved between splits in place (labels follow their images).",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
        )
        if reply != QMessageBox.StandardButton.Yes:
            return
        try:
            counts = collect_io.rebalance_splits(ds_dir)
        except Exception as e:
            QMessageBox.warning(self, "MineSight", str(e))
            return
        QMessageBox.information(
            self, "Rebalance splits",
            f"Done: train {counts['train']}, valid {counts['valid']}, test {counts['test']}.",
        )
        self._show_dataset(name)
        self.datasetsChanged.emit()

    def _render(self, h: DatasetHealth) -> None:
        self.warnings.setText(
            "\n".join(f"⚠ {w}" for w in h.warnings) if h.warnings else "✓ No issues detected"
        )
        self.warnings.setStyleSheet("color:#ffb347;" if h.warnings else "color:#2ecc40;")

        self.table.setRowCount(len(SPLITS))
        for i, split in enumerate(SPLITS):
            st = h.splits.get(split)
            vals = [split] + (
                [str(st.images), str(st.label_files), str(st.empty), str(st.boxes)] if st else ["-"] * 4
            )
            for j, v in enumerate(vals):
                self.table.setItem(i, j, QTableWidgetItem(v))

        self.fig.clear()
        ax = self.fig.add_subplot(111)
        n = max(h.nc, len(h.names))
        if n:
            idx = list(range(n))
            labels = [(h.names[i] if i < len(h.names) else f"cls {i}")[:24] for i in idx]
            bottom = [0] * n
            colors = {"train": "#4aedd9", "valid": "#ffb347", "test": "#ff4136"}
            for split in SPLITS:
                st = h.splits.get(split)
                counts = [st.hist.get(i, 0) if st else 0 for i in idx]
                ax.bar(idx, counts, bottom=bottom, label=split, color=colors[split])
                bottom = [b + c for b, c in zip(bottom, counts)]
            ax.set_xticks(idx)
            ax.set_xticklabels(labels, rotation=30, ha="right", fontsize=8)
            ax.set_ylabel("boxes")
            ax.set_title(f"{h.name} - class balance")
            ax.legend(fontsize=8)
        self.canvas.draw_idle()
