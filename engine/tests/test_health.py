"""Tests for dataset health analysis (stats + labeling-bug warnings)."""
from __future__ import annotations

from conftest import make_dataset
from minesight_gui import health


def test_analyze_counts_and_flags_issues(datasets_dir):
    ds = make_dataset(
        datasets_dir / "noisy",
        # class 1 is junk Roboflow text; class 2 ("rare") will have too few boxes
        ["iron_ore", "This dataset was exported via roboflow.com on ...", "rare_ore"],
        {
            "train": [
                ("a", [(0, 0.5, 0.5, 0.1, 0.1)]),
                ("b", [(0, 0.4, 0.4, 0.1, 0.1)]),
                ("c", [(2, 0.3, 0.3, 0.1, 0.1)]),  # the only rare box
            ],
            "valid": [("v", [])],  # empty/background
            "test": [("t", [(0, 0.5, 0.5, 0.1, 0.1)])],
        },
    )
    h = health.analyze(ds)

    assert h.nc == 3
    assert h.splits["train"].boxes == 3
    assert h.splits["train"].hist[0] == 2
    assert h.splits["valid"].empty == 1
    warnings = " ".join(h.warnings).lower()
    assert "junk" in warnings or "roboflow" in warnings  # junk class name flagged
    assert "rare_ore" in warnings  # too-few-boxes flagged


def test_analyze_clean_dataset_has_no_warnings(datasets_dir):
    items = [(f"i{i}", [(0, 0.5, 0.5, 0.1, 0.1)]) for i in range(40)]
    ds = make_dataset(datasets_dir / "clean", ["iron_ore"],
                      {"train": items, "valid": items[:5], "test": items[:5]})
    h = health.analyze(ds)
    assert h.nc == 1
    # iron has plenty of boxes, splits non-empty -> no warnings
    assert h.warnings == []
