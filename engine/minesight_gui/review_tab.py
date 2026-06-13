"""Active-learning Review tab: correct the model's mistakes and feed them back.

Frames flagged during testing (F9 hotkey, GUI button, or auto-captured when the
model is unsure) land in engine/review/ as PNG + predicted boxes. Here you fix
them - delete false positives, drag/draw the right boxes, relabel, or mark the
whole frame as a hard negative - and save straight into a dataset's train split.
Corrections are the highest-value training data because they target real failures.
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

import yaml
from PySide6.QtCore import QRectF, Qt, Signal
from PySide6.QtGui import QBrush, QColor, QFont, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QComboBox,
    QGraphicsRectItem,
    QGraphicsScene,
    QGraphicsSimpleTextItem,
    QGraphicsView,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QListWidgetItem,
    QMessageBox,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from .constants import DATASETS_DIR, ENGINE_DIR
from .gallery import color_for
from .health import list_datasets
from .widgets import LogView

REVIEW_DIR = ENGINE_DIR / "review"


def _dataset_classes(ds_dir: Path) -> list[str]:
    try:
        names = yaml.safe_load((ds_dir / "data.yaml").read_text(encoding="utf-8")).get("names") or []
        if isinstance(names, dict):
            names = [names[k] for k in sorted(names)]
        return [str(n) for n in names]
    except Exception:
        return []


class BoxItem(QGraphicsRectItem):
    """A movable, selectable, relabelable bounding box over the image."""

    def __init__(self, rect: QRectF, cls_index: int, classes: list[str]):
        super().__init__(rect)
        self.classes = classes
        self.cls_index = cls_index
        self.setFlags(
            QGraphicsRectItem.GraphicsItemFlag.ItemIsMovable
            | QGraphicsRectItem.GraphicsItemFlag.ItemIsSelectable
        )
        self.label = QGraphicsSimpleTextItem(self)
        self.label.setFont(QFont("Segoe UI", 9))
        self.label.setFlag(QGraphicsRectItem.GraphicsItemFlag.ItemIgnoresTransformations)
        self.set_class(cls_index)

    def set_class(self, idx: int) -> None:
        self.cls_index = idx
        name = self.classes[idx] if 0 <= idx < len(self.classes) else f"cls{idx}"
        color = color_for(name)
        pen = QPen(color)
        pen.setWidth(2)
        pen.setCosmetic(True)
        self.setPen(pen)
        self.label.setText(name)
        self.label.setBrush(QBrush(color))
        self.label.setPos(self.rect().left(), self.rect().top() - 16)

    def paint(self, painter, option, widget=None) -> None:
        super().paint(painter, option, widget)
        if self.isSelected():
            hl = QPen(QColor(255, 255, 255))
            hl.setWidth(1)
            hl.setCosmetic(True)
            hl.setStyle(Qt.PenStyle.DashLine)
            painter.setPen(hl)
            painter.drawRect(self.rect())

    def yolo(self, img_w: int, img_h: int) -> str:
        r = self.sceneBoundingRect()  # accounts for any move
        cx = (r.x() + r.width() / 2) / img_w
        cy = (r.y() + r.height() / 2) / img_h
        return f"{self.cls_index} {cx:.6f} {cy:.6f} {r.width() / img_w:.6f} {r.height() / img_h:.6f}"


class EditorView(QGraphicsView):
    """Image canvas: select/move boxes, or drag to draw new ones in Draw mode."""

    boxAdded = Signal(object)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setScene(QGraphicsScene(self))
        self.setRenderHints(QPainter.RenderHint.Antialiasing | QPainter.RenderHint.SmoothPixmapTransform)
        self.setBackgroundBrush(QBrush(QColor(20, 20, 20)))
        self.setDragMode(QGraphicsView.DragMode.RubberBandDrag)
        self.draw_mode = False
        self.classes: list[str] = []
        self.current_class = 0
        self._draw_start = None
        self._draw_item = None
        self._pix_w = 0
        self._pix_h = 0

    def load(self, pixmap: QPixmap) -> None:
        self.scene().clear()
        self.scene().addPixmap(pixmap)
        self._pix_w, self._pix_h = pixmap.width(), pixmap.height()
        self.scene().setSceneRect(0, 0, self._pix_w, self._pix_h)
        self.fitInView(self.scene().sceneRect(), Qt.AspectRatioMode.KeepAspectRatio)

    def add_box(self, rect: QRectF, cls_index: int) -> BoxItem:
        item = BoxItem(rect, cls_index, self.classes)
        self.scene().addItem(item)
        return item

    def boxes(self) -> list[BoxItem]:
        return [i for i in self.scene().items() if isinstance(i, BoxItem)]

    def wheelEvent(self, event) -> None:
        factor = 1.2 if event.angleDelta().y() > 0 else 1 / 1.2
        self.scale(factor, factor)

    def mousePressEvent(self, event) -> None:
        if self.draw_mode and event.button() == Qt.MouseButton.LeftButton:
            self._draw_start = self.mapToScene(event.pos())
            self._draw_item = QGraphicsRectItem(QRectF(self._draw_start, self._draw_start))
            pen = QPen(QColor(255, 255, 255))
            pen.setCosmetic(True)
            self._draw_item.setPen(pen)
            self.scene().addItem(self._draw_item)
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:
        if self._draw_item is not None:
            self._draw_item.setRect(QRectF(self._draw_start, self.mapToScene(event.pos())).normalized())
            return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:
        if self._draw_item is not None:
            rect = self._draw_item.rect().normalized()
            self.scene().removeItem(self._draw_item)
            self._draw_item = None
            self._draw_start = None
            if rect.width() >= 4 and rect.height() >= 4:
                self.boxAdded.emit(self.add_box(rect, self.current_class))
            return
        super().mouseReleaseEvent(event)


class ReviewTab(QWidget):
    datasetsChanged = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._stem: str | None = None
        self._img_w = 0
        self._img_h = 0

        layout = QVBoxLayout(self)

        top = QHBoxLayout()
        top.addWidget(QLabel("Save into:"))
        self.target = QComboBox()
        self.target.setMinimumWidth(180)
        self.target.currentIndexChanged.connect(self._target_changed)
        top.addWidget(self.target)
        top.addWidget(QLabel("Class:"))
        self.class_combo = QComboBox()
        self.class_combo.setMinimumWidth(150)
        self.class_combo.currentIndexChanged.connect(self._class_changed)
        top.addWidget(self.class_combo)
        self.draw_btn = QPushButton("✏ Draw box")
        self.draw_btn.setCheckable(True)
        self.draw_btn.setToolTip("Drag on the image to add a box for a missed ore (uses the Class above)")
        self.draw_btn.toggled.connect(self._toggle_draw)
        top.addWidget(self.draw_btn)
        del_btn = QPushButton("🗑 Delete box")
        del_btn.setShortcut("Del")
        del_btn.setToolTip("Remove the selected box (false positive)")
        del_btn.clicked.connect(self._delete_selected)
        top.addWidget(del_btn)
        top.addStretch(1)
        layout.addLayout(top)

        body = QHBoxLayout()
        self.listw = QListWidget()
        self.listw.setMaximumWidth(230)
        self.listw.currentItemChanged.connect(self._item_changed)
        body.addWidget(self.listw)
        self.view = EditorView()
        body.addWidget(self.view, 1)
        layout.addLayout(body, 1)

        actions = QHBoxLayout()
        save_btn = QPushButton("✓ Save correction → dataset")
        save_btn.setToolTip("Write the corrected image + labels into the target dataset's train split")
        save_btn.clicked.connect(self._save)
        actions.addWidget(save_btn)
        neg_btn = QPushButton("⊘ All wrong → hard negative")
        neg_btn.setToolTip("No real ore here: save the frame with an empty label so the model stops firing on it")
        neg_btn.clicked.connect(self._save_negative)
        actions.addWidget(neg_btn)
        discard_btn = QPushButton("✕ Discard")
        discard_btn.setToolTip("Throw this frame away without saving")
        discard_btn.clicked.connect(self._discard)
        actions.addWidget(discard_btn)
        actions.addStretch(1)
        refresh = QPushButton("⟳ Refresh")
        refresh.clicked.connect(self.refresh)
        actions.addWidget(refresh)
        self.count_label = QLabel("")
        actions.addWidget(self.count_label)
        layout.addLayout(actions)

        self.log = LogView()
        self.log.setMaximumHeight(90)
        layout.addWidget(self.log)

        self.refresh_targets()
        self.refresh()

    # --- target dataset + classes --------------------------------------------

    def refresh_targets(self) -> None:
        current = self.target.currentText()
        self.target.blockSignals(True)
        self.target.clear()
        for ds in list_datasets():
            self.target.addItem(ds.name, str(ds))
        self.target.blockSignals(False)
        if current:
            idx = self.target.findText(current)
            self.target.setCurrentIndex(idx if idx >= 0 else self.target.count() - 1)
        elif self.target.count():
            self.target.setCurrentIndex(self.target.count() - 1)
        self._target_changed()

    def _classes(self) -> list[str]:
        data = self.target.currentData()
        return _dataset_classes(Path(data)) if data else []

    def _target_changed(self) -> None:
        classes = self._classes()
        self.view.classes = classes
        self.class_combo.blockSignals(True)
        self.class_combo.clear()
        self.class_combo.addItems(classes)
        self.class_combo.blockSignals(False)
        self.view.current_class = self.class_combo.currentIndex()

    def _class_changed(self, idx: int) -> None:
        self.view.current_class = idx
        # Relabel the selected box, if any
        for item in self.view.scene().selectedItems():
            if isinstance(item, BoxItem):
                item.set_class(idx)

    def _toggle_draw(self, on: bool) -> None:
        self.view.draw_mode = on
        self.view.setDragMode(
            QGraphicsView.DragMode.NoDrag if on else QGraphicsView.DragMode.RubberBandDrag
        )

    def _delete_selected(self) -> None:
        for item in self.view.scene().selectedItems():
            if isinstance(item, BoxItem):
                self.view.scene().removeItem(item)

    # --- review queue ----------------------------------------------------------

    def refresh(self) -> None:
        current = self._stem
        self.listw.clear()
        if REVIEW_DIR.exists():
            stems = sorted((p.stem for p in REVIEW_DIR.glob("*.json")), reverse=True)
            for stem in stems:
                reason = "auto" if stem.endswith("_auto") else "flagged"
                item = QListWidgetItem(f"{'🤖' if reason == 'auto' else '🚩'} {stem.split('_')[1]}")
                item.setData(Qt.ItemDataRole.UserRole, stem)
                self.listw.addItem(item)
        self.count_label.setText(f"{self.listw.count()} to review")
        if current:
            for row in range(self.listw.count()):
                if self.listw.item(row).data(Qt.ItemDataRole.UserRole) == current:
                    self.listw.setCurrentRow(row)
                    return
        if self.listw.count():
            self.listw.setCurrentRow(0)
        else:
            self.view.scene().clear()
            self._stem = None

    def _item_changed(self, current: QListWidgetItem | None, _prev=None) -> None:
        if current is None:
            return
        self._stem = current.data(Qt.ItemDataRole.UserRole)
        self._load_review(self._stem)

    def _load_review(self, stem: str) -> None:
        png = REVIEW_DIR / f"{stem}.png"
        meta_path = REVIEW_DIR / f"{stem}.json"
        if not png.exists() or not meta_path.exists():
            return
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        pix = QPixmap(str(png))
        self._img_w = meta.get("frame_w", pix.width())
        self._img_h = meta.get("frame_h", pix.height())
        self.view.load(pix)
        classes = self._classes()
        for pred in meta.get("predictions", []):
            label = pred.get("label", "")
            idx = classes.index(label) if label in classes else 0
            x, y, w, h = pred["x"], pred["y"], pred["w"], pred["h"]
            self.view.add_box(QRectF(x - w / 2, y - h / 2, w, h), idx)
        self.draw_btn.setChecked(False)

    # --- save / discard --------------------------------------------------------

    def _target_dir(self) -> Path | None:
        data = self.target.currentData()
        return Path(data) if data else None

    def _save(self) -> None:
        ds = self._target_dir()
        if ds is None or self._stem is None:
            return
        lines = [b.yolo(self._img_w, self._img_h) for b in self.view.boxes()]
        self._write(ds, lines)
        self.log.append_line(f"[saved {len(lines)} box(es) → {ds.name}/train]")

    def _save_negative(self) -> None:
        ds = self._target_dir()
        if ds is None or self._stem is None:
            return
        self._write(ds, [])
        self.log.append_line(f"[saved hard negative → {ds.name}/train]")

    def _write(self, ds: Path, lines: list[str]) -> None:
        (ds / "train" / "images").mkdir(parents=True, exist_ok=True)
        (ds / "train" / "labels").mkdir(parents=True, exist_ok=True)
        src_png = REVIEW_DIR / f"{self._stem}.png"
        shutil.copy2(src_png, ds / "train" / "images" / f"{self._stem}.png")
        (ds / "train" / "labels" / f"{self._stem}.txt").write_text(
            "\n".join(lines) + ("\n" if lines else ""), encoding="utf-8"
        )
        self._remove_review(self._stem)
        self.datasetsChanged.emit()
        self.refresh()

    def _discard(self) -> None:
        if self._stem:
            self._remove_review(self._stem)
            self.refresh()

    @staticmethod
    def _remove_review(stem: str) -> None:
        (REVIEW_DIR / f"{stem}.png").unlink(missing_ok=True)
        (REVIEW_DIR / f"{stem}.json").unlink(missing_ok=True)

    def shutdown(self) -> None:
        pass
