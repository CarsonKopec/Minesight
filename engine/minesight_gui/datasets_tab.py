from __future__ import annotations

import logging
from pathlib import Path

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
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
from .workers import Task

log = logging.getLogger("minesight.gui.datasets")


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
        self.merge_btn = QPushButton("📥 Merge into…")
        self.merge_btn.setToolTip(
            "Copy every image in the selected dataset into another dataset,\n"
            "remapping classes by name (classes the target lacks are added).\n"
            "Then rebalance the target's splits for a clean train/valid/test."
        )
        self.merge_btn.clicked.connect(self._merge)
        lv.addWidget(self.merge_btn)
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

        self._task: Task | None = None
        self._analyze_tasks: list[Task] = []
        self.refresh()

    def _busy(self, on: bool, msg: str = "") -> None:
        self.rebalance_btn.setEnabled(not on)
        self.merge_btn.setEnabled(not on)
        if on:
            self.warnings.setStyleSheet("color:#4aedd9;")
            self.warnings.setText(f"⏳ {msg}")

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

    def _selected_name(self) -> str | None:
        item = self.listw.currentItem()
        return item.text() if item else None

    def _show_dataset(self, name: str) -> None:
        if not name:
            return
        # Analysis reads every label file - run it off the UI thread so big
        # datasets don't freeze the window. Stale results (user moved on) are
        # discarded by the name check in _analyze_done.
        self.warnings.setStyleSheet("color:#4aedd9;")
        self.warnings.setText(f"⏳ analyzing {name}…")
        task = Task(analyze, DATASETS_DIR / name, parent=self)
        self._analyze_tasks.append(task)
        task.done.connect(lambda health, n=name, t=task: self._analyze_done(n, health, t))
        task.failed.connect(lambda msg, t=task: self._analyze_failed(msg, t))
        task.start()

    def _analyze_done(self, name: str, health: DatasetHealth, task: Task) -> None:
        if task in self._analyze_tasks:
            self._analyze_tasks.remove(task)
        if name != self._selected_name():
            return  # selection changed while analyzing - drop stale result
        self._render(health)

    def _analyze_failed(self, msg: str, task: Task) -> None:
        if task in self._analyze_tasks:
            self._analyze_tasks.remove(task)
        log.warning("Dataset analysis failed: %s", msg)

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
        log.info("Rebalancing splits for '%s'", name)
        self._busy(True, f"rebalancing {name}…")
        self._task = Task(collect_io.rebalance_splits, ds_dir, parent=self)
        self._task.done.connect(lambda counts: self._rebalance_done(name, counts))
        self._task.failed.connect(self._op_failed)
        self._task.start()

    def _rebalance_done(self, name: str, counts: dict) -> None:
        self._busy(False)
        log.info("Rebalanced '%s': %s", name, counts)
        QMessageBox.information(
            self, "Rebalance splits",
            f"Done: train {counts['train']}, valid {counts['valid']}, test {counts['test']}.",
        )
        self._show_dataset(name)
        self.datasetsChanged.emit()

    def _op_failed(self, msg: str) -> None:
        self._busy(False)
        item = self.listw.currentItem()
        if item:
            self._show_dataset(item.text())
        QMessageBox.warning(self, "MineSight", msg)

    def shutdown(self) -> None:
        # Let any in-flight background tasks finish so Qt doesn't warn about
        # threads destroyed while running.
        for task in list(self._analyze_tasks):
            task.wait(2000)
        if self._task is not None:
            self._task.wait(5000)

    def _merge(self) -> None:
        item = self.listw.currentItem()
        if item is None:
            return
        src_name = item.text()
        targets = [d.name for d in list_datasets() if d.name != src_name]
        if not targets:
            QMessageBox.information(self, "MineSight", "Need another dataset to merge into.")
            return
        target, ok = QInputDialog.getItem(
            self, "Merge dataset", f"Copy '{src_name}' into:", targets, len(targets) - 1, False
        )
        if not ok:
            return
        log.info("Merging '%s' into '%s'", src_name, target)
        self._busy(True, f"merging {src_name} → {target}…")
        self._task = Task(collect_io.merge_into, DATASETS_DIR / src_name, DATASETS_DIR / target,
                          parent=self)
        self._task.done.connect(lambda copied: self._merge_done(src_name, target, copied))
        self._task.failed.connect(self._op_failed)
        self._task.start()

    def _merge_done(self, src_name: str, target: str, copied: int) -> None:
        self._busy(False)
        log.info("Merged %d image(s) from '%s' into '%s'", copied, src_name, target)
        remove = QMessageBox.question(
            self, "Merge dataset",
            f"Merged {copied} image(s) into '{target}'.\n\nDelete the source '{src_name}' now?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if remove == QMessageBox.StandardButton.Yes:
            import shutil
            shutil.rmtree(DATASETS_DIR / src_name, ignore_errors=True)
            log.info("Deleted merged source '%s'", src_name)
        self.refresh()
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
