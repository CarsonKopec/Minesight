"""Cohesive dark theme for the Control Panel - a Fusion palette + a global QSS
stylesheet so every tab shares one clean, card-based look."""
from __future__ import annotations

from PySide6.QtGui import QColor, QFont, QPalette

# --- palette tokens --------------------------------------------------------
BG = "#1b1c1f"          # window
SURFACE = "#26282c"     # cards / group boxes
SURFACE_HI = "#34373c"  # buttons / raised
INPUT = "#16171a"       # text fields, lists
BORDER = "#34373c"
BORDER_HI = "#41454c"
TEXT = "#dcdde0"
TEXT_DIM = "#9aa0a6"
ACCENT = "#4aedd9"
ACCENT_INK = "#0f201c"  # text on the accent
SELECT = "#2f5d57"


def dark_palette() -> QPalette:
    p = QPalette()
    p.setColor(QPalette.ColorRole.Window, QColor(BG))
    p.setColor(QPalette.ColorRole.WindowText, QColor(TEXT))
    p.setColor(QPalette.ColorRole.Base, QColor(INPUT))
    p.setColor(QPalette.ColorRole.AlternateBase, QColor(SURFACE))
    p.setColor(QPalette.ColorRole.Text, QColor(TEXT))
    p.setColor(QPalette.ColorRole.Button, QColor(SURFACE_HI))
    p.setColor(QPalette.ColorRole.ButtonText, QColor(TEXT))
    p.setColor(QPalette.ColorRole.Highlight, QColor(ACCENT))
    p.setColor(QPalette.ColorRole.HighlightedText, QColor(ACCENT_INK))
    p.setColor(QPalette.ColorRole.ToolTipBase, QColor(SURFACE))
    p.setColor(QPalette.ColorRole.ToolTipText, QColor(TEXT))
    p.setColor(QPalette.ColorRole.PlaceholderText, QColor(TEXT_DIM))
    p.setColor(QPalette.ColorRole.Link, QColor(ACCENT))
    return p


# Note: font family/size is set via app.setFont (not QSS) so widgets that set
# their own font - e.g. the monospace LogView - keep it.
STYLESHEET = f"""
QWidget {{ background-color: {BG}; color: {TEXT}; }}
QMainWindow, QDialog, QMessageBox {{ background-color: {BG}; }}
QLabel {{ background: transparent; }}

/* Tabs ------------------------------------------------------------------- */
QTabWidget::pane {{
    border: 1px solid {BORDER};
    border-radius: 10px;
    top: -1px;
    background: {BG};
}}
QTabBar {{ qproperty-drawBase: 0; }}
QTabBar::tab {{
    background: transparent;
    color: {TEXT_DIM};
    padding: 8px 15px;
    margin: 0 2px 4px 0;
    border: none;
    border-radius: 8px;
    font-weight: 500;
}}
QTabBar::tab:hover {{ color: {TEXT}; background: {SURFACE}; }}
QTabBar::tab:selected {{ color: {ACCENT_INK}; background: {ACCENT}; font-weight: 600; }}

/* Cards (group boxes) - outlined so nested widgets never mismatch the fill -- */
QGroupBox {{
    background: transparent;
    border: 1px solid {BORDER};
    border-radius: 10px;
    margin-top: 14px;
    padding: 12px 12px 10px 12px;
    font-weight: 600;
}}
QGroupBox::title {{
    subcontrol-origin: margin;
    subcontrol-position: top left;
    left: 12px;
    padding: 1px 6px;
    color: {ACCENT};
}}

/* Buttons ---------------------------------------------------------------- */
QPushButton {{
    background: {SURFACE_HI};
    color: {TEXT};
    border: 1px solid {BORDER_HI};
    border-radius: 8px;
    padding: 7px 14px;
    font-weight: 500;
}}
QPushButton:hover {{ background: #3e4248; border-color: {ACCENT}; }}
QPushButton:pressed {{ background: {SURFACE}; }}
QPushButton:disabled {{ background: {SURFACE}; color: #676d74; border-color: {BORDER}; }}
QPushButton:checked {{ background: {ACCENT}; color: {ACCENT_INK}; border-color: {ACCENT}; }}

/* Inputs ----------------------------------------------------------------- */
QLineEdit, QComboBox, QSpinBox, QDoubleSpinBox, QPlainTextEdit, QTextEdit {{
    background: {INPUT};
    color: {TEXT};
    border: 1px solid {BORDER};
    border-radius: 7px;
    padding: 5px 8px;
    selection-background-color: {ACCENT};
    selection-color: {ACCENT_INK};
}}
QLineEdit:focus, QComboBox:focus, QSpinBox:focus,
QDoubleSpinBox:focus, QPlainTextEdit:focus, QTextEdit:focus {{
    border-color: {ACCENT};
}}
QComboBox QAbstractItemView {{
    background: {SURFACE};
    border: 1px solid {BORDER};
    selection-background-color: {ACCENT};
    selection-color: {ACCENT_INK};
    outline: none;
}}

/* Checkboxes ------------------------------------------------------------- */
QCheckBox {{ spacing: 7px; background: transparent; }}
QCheckBox::indicator {{
    width: 16px; height: 16px;
    border: 1px solid {BORDER_HI}; border-radius: 4px; background: {INPUT};
}}
QCheckBox::indicator:hover {{ border-color: {ACCENT}; }}
QCheckBox::indicator:checked {{ background: {ACCENT}; border-color: {ACCENT}; }}

/* Lists / tables --------------------------------------------------------- */
QListWidget, QTableWidget, QTreeWidget {{
    background: {INPUT};
    border: 1px solid {BORDER};
    border-radius: 8px;
    alternate-background-color: #1f2024;
    gridline-color: #2a2c30;
    outline: none;
}}
QListWidget::item, QTableWidget::item {{ padding: 4px; }}
QListWidget::item:selected, QTableWidget::item:selected {{ background: {SELECT}; color: #ffffff; }}
QListWidget::item:hover, QTableWidget::item:hover {{ background: #232529; }}
QHeaderView::section {{
    background: {SURFACE};
    color: {TEXT_DIM};
    padding: 6px 8px;
    border: none;
    border-right: 1px solid #2a2c30;
    border-bottom: 1px solid {BORDER};
    font-weight: 600;
}}
QTableCornerButton::section {{ background: {SURFACE}; border: none; }}

/* Progress --------------------------------------------------------------- */
QProgressBar {{
    background: {INPUT};
    border: 1px solid {BORDER};
    border-radius: 8px;
    text-align: center;
    color: {TEXT};
    min-height: 18px;
}}
QProgressBar::chunk {{ background: {ACCENT}; border-radius: 7px; }}

/* Scrollbars ------------------------------------------------------------- */
QScrollBar:vertical {{ background: transparent; width: 11px; margin: 2px; }}
QScrollBar::handle:vertical {{ background: {BORDER_HI}; border-radius: 5px; min-height: 28px; }}
QScrollBar::handle:vertical:hover {{ background: {ACCENT}; }}
QScrollBar:horizontal {{ background: transparent; height: 11px; margin: 2px; }}
QScrollBar::handle:horizontal {{ background: {BORDER_HI}; border-radius: 5px; min-width: 28px; }}
QScrollBar::handle:horizontal:hover {{ background: {ACCENT}; }}
QScrollBar::add-line, QScrollBar::sub-line {{ width: 0; height: 0; }}
QScrollBar::add-page, QScrollBar::sub-page {{ background: transparent; }}

/* Splitter / status bar / tooltip --------------------------------------- */
QSplitter::handle {{ background: {BG}; }}
QSplitter::handle:horizontal {{ width: 6px; }}
QSplitter::handle:vertical {{ height: 6px; }}
QSplitter::handle:hover {{ background: {ACCENT}; }}
QStatusBar {{ background: {INPUT}; color: {TEXT_DIM}; border-top: 1px solid {BORDER}; }}
QStatusBar::item {{ border: none; }}
QToolTip {{
    background: {SURFACE}; color: {TEXT};
    border: 1px solid {ACCENT}; border-radius: 6px; padding: 5px 8px;
}}
"""


def apply(app) -> None:
    app.setStyle("Fusion")
    app.setPalette(dark_palette())
    font = QFont("Segoe UI")
    font.setPointSize(10)
    app.setFont(font)
    app.setStyleSheet(STYLESHEET)
