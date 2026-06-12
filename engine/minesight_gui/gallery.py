"""Zoomable viewer for dataset images with their YOLO boxes drawn on top."""
from __future__ import annotations

import colorsys
import json
from pathlib import Path

import yaml
from PySide6.QtCore import QRectF, Qt, QUrl, Signal
from PySide6.QtGui import QBrush, QColor, QDesktopServices, QFont, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QGraphicsScene,
    QGraphicsView,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QPushButton,
    QSplitter,
    QVBoxLayout,
    QWidget,
)

from .constants import DATASETS_DIR

LABEL_COLORS = {
    "diamond": "#4aedd9",
    "emerald": "#2ecc40",
    "gold": "#ffd700",
    "iron": "#d8c8b8",
    "coal": "#9a9a9a",
    "redstone": "#ff4136",
    "lapis": "#3d5afe",
    "copper": "#e07b4f",
}


def color_for(label: str) -> QColor:
    low = label.lower()
    for key, hexcol in LABEL_COLORS.items():
        if key in low:
            return QColor(hexcol)
    hue = (hash(label) % 360) / 360.0
    r, g, b = colorsys.hsv_to_rgb(hue, 0.7, 1.0)
    return QColor(int(r * 255), int(g * 255), int(b * 255))


class ZoomView(QGraphicsView):
    """Wheel-zoom, drag-pan, double-click to refit."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setScene(QGraphicsScene(self))
        self.setRenderHints(
            QPainter.RenderHint.Antialiasing | QPainter.RenderHint.SmoothPixmapTransform
        )
        self.setDragMode(QGraphicsView.DragMode.ScrollHandDrag)
        self.setTransformationAnchor(QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setBackgroundBrush(QBrush(QColor(20, 20, 20)))

    def wheelEvent(self, event) -> None:
        factor = 1.2 if event.angleDelta().y() > 0 else 1 / 1.2
        self.scale(factor, factor)

    def fit(self) -> None:
        rect = self.scene().itemsBoundingRect()
        if not rect.isEmpty():
            self.fitInView(rect, Qt.AspectRatioMode.KeepAspectRatio)

    def mouseDoubleClickEvent(self, event) -> None:
        self.fit()
        super().mouseDoubleClickEvent(event)


def _dataset_classes(ds_dir: Path) -> list[str]:
    cj = ds_dir / "classes.json"
    if cj.exists():
        try:
            return list(json.loads(cj.read_text(encoding="utf-8"))["classes"])
        except Exception:
            pass
    dy = ds_dir / "data.yaml"
    if dy.exists():
        try:
            names = yaml.safe_load(dy.read_text(encoding="utf-8")).get("names") or []
            if isinstance(names, dict):
                names = [names[k] for k in sorted(names)]
            return [str(n) for n in names]
        except Exception:
            pass
    return []


def _dataset_images(ds_dir: Path) -> list[Path]:
    out: list[Path] = []
    for sub in ("pool", "train", "valid", "test"):
        img_dir = ds_dir / sub / "images"
        if img_dir.exists():
            out.extend(p for p in img_dir.iterdir() if p.suffix.lower() in (".png", ".jpg", ".jpeg"))
    out.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    return out


class ImageInspector(QWidget):
    """Dataset browser: image list + zoomable view with boxes and labels."""

    def __init__(self, parent=None):
        super().__init__(parent)
        self._classes: list[str] = []
        self._dir: Path | None = None

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        top = QHBoxLayout()
        top.addWidget(QLabel("Browse:"))
        self.source = QComboBox()
        self.source.setMinimumWidth(220)
        self.source.currentIndexChanged.connect(self._source_changed)
        top.addWidget(self.source)
        refresh = QPushButton("⟳")
        refresh.setFixedWidth(32)
        refresh.clicked.connect(lambda: self.refresh_sources())
        top.addWidget(refresh)
        self.follow = QCheckBox("Follow live")
        self.follow.setChecked(True)
        self.follow.setToolTip("Show every new capture as it is saved")
        top.addWidget(self.follow)
        delete_btn = QPushButton("🗑 Delete image")
        delete_btn.setToolTip("Remove this image and its label from the dataset (Del)")
        delete_btn.setShortcut("Del")
        delete_btn.clicked.connect(self.delete_current)
        top.addWidget(delete_btn)
        open_btn = QPushButton("↗ Open externally")
        open_btn.setToolTip("Open the raw PNG in your default image viewer / media player")
        open_btn.clicked.connect(self.open_current)
        top.addWidget(open_btn)
        top.addStretch(1)
        self.counter = QLabel("")
        top.addWidget(self.counter)
        layout.addLayout(top)

        split = QSplitter(Qt.Orientation.Horizontal)
        self.listw = QListWidget()
        self.listw.setMaximumWidth(240)
        self.listw.currentItemChanged.connect(self._item_changed)
        split.addWidget(self.listw)
        self.view = ZoomView()
        split.addWidget(self.view)
        split.setSizes([200, 700])
        layout.addWidget(split, 1)

        self.refresh_sources()

    # --- sources --------------------------------------------------------------

    def refresh_sources(self, select: str | None = None) -> None:
        if select is None and self.source.currentData():
            select = Path(self.source.currentData()).name
        self.source.blockSignals(True)
        self.source.clear()
        if DATASETS_DIR.exists():
            for ds in sorted(DATASETS_DIR.iterdir()):
                if ds.is_dir() and _dataset_images(ds):
                    self.source.addItem(ds.name, str(ds))
        self.source.blockSignals(False)
        if select:
            idx = self.source.findText(select)
            if idx >= 0:
                self.source.setCurrentIndex(idx)
        self._source_changed()

    def _source_changed(self) -> None:
        data = self.source.currentData()
        self._dir = Path(data) if data else None
        self.listw.clear()
        if self._dir is None:
            self.view.scene().clear()
            self.counter.setText("")
            return
        self._classes = _dataset_classes(self._dir)
        images = _dataset_images(self._dir)
        for img in images:
            item = QListWidgetItem(img.name)
            item.setData(Qt.ItemDataRole.UserRole, str(img))
            self.listw.addItem(item)
        self.counter.setText(f"{len(images)} images")
        if images:
            self.listw.setCurrentRow(0)

    # --- live updates during collection ----------------------------------------

    def on_live_capture(self, dataset_name: str, file_name: str) -> None:
        if self._dir is None or self._dir.name != dataset_name:
            self.refresh_sources(select=dataset_name)
            return
        path = self._dir / "pool" / "images" / file_name
        if not path.exists():
            return
        item = QListWidgetItem(file_name)
        item.setData(Qt.ItemDataRole.UserRole, str(path))
        self.listw.insertItem(0, item)
        self.counter.setText(f"{self.listw.count()} images")
        if self.follow.isChecked():
            self.listw.setCurrentItem(item)  # triggers _item_changed -> show

    # --- rendering --------------------------------------------------------------

    def _item_changed(self, current: QListWidgetItem | None, _prev=None) -> None:
        if current is not None:
            self._show(Path(current.data(Qt.ItemDataRole.UserRole)))

    def open_current(self) -> None:
        item = self.listw.currentItem()
        if item is not None:
            QDesktopServices.openUrl(
                QUrl.fromLocalFile(str(item.data(Qt.ItemDataRole.UserRole)))
            )

    def delete_current(self) -> None:
        """Remove the selected image + label from disk (bad-frame pruning)."""
        item = self.listw.currentItem()
        if item is None:
            return
        img_path = Path(item.data(Qt.ItemDataRole.UserRole))
        label_path = img_path.parent.parent / "labels" / (img_path.stem + ".txt")
        img_path.unlink(missing_ok=True)
        label_path.unlink(missing_ok=True)
        row = self.listw.row(item)
        self.listw.takeItem(row)
        self.counter.setText(f"{self.listw.count()} images")
        if self.listw.count() == 0:
            self.view.scene().clear()

    def _show(self, img_path: Path) -> None:
        scene = self.view.scene()
        scene.clear()
        pix = QPixmap(str(img_path))
        if pix.isNull():
            return
        scene.addPixmap(pix)
        w, h = pix.width(), pix.height()

        label_path = img_path.parent.parent / "labels" / (img_path.stem + ".txt")
        if label_path.exists():
            for line in label_path.read_text(encoding="utf-8").splitlines():
                parts = line.split()
                if len(parts) != 5:
                    continue
                cls = int(parts[0])
                cx, cy, bw, bh = (float(v) for v in parts[1:])
                name = self._classes[cls] if cls < len(self._classes) else f"cls {cls}"
                color = color_for(name)
                pen = QPen(color)
                pen.setWidth(2)
                pen.setCosmetic(True)  # constant width regardless of zoom
                rect = QRectF((cx - bw / 2) * w, (cy - bh / 2) * h, bw * w, bh * h)
                scene.addRect(rect, pen)
                text = scene.addSimpleText(name, QFont("Segoe UI", 9))
                text.setBrush(QBrush(color))
                text.setPos(rect.left(), rect.top() - 16)
                # keep labels readable at any zoom level
                text.setFlag(text.GraphicsItemFlag.ItemIgnoresTransformations)
        # The scene rect never shrinks on its own; without this, images
        # smaller than a previously viewed one sit off-center in a stale,
        # oversized scene.
        scene.setSceneRect(scene.itemsBoundingRect())
        self.view.fit()
