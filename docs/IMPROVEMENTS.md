# MineSight — Improvement Backlog

Tracking quality/robustness work now that the spec roadmap (Phases 1–5) is
complete. Tick items as they land; keep the rationale so we remember *why*.

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

---

## High impact

### [x] 1. Diversify collected data (fix the "too clean" distribution)  — done 2026-06-13 (mod 0.10.1)
**Problem:** the collector aimed dead-center at every ore behind a narrow
central aim gate, so the model learned "ore = a centered, well-framed,
fully-visible block" — a train/play mismatch that silently capped accuracy.

**What shipped:**
- Off-center framing: aim jitter widened to ±18° yaw / ±14° pitch, and the
  capture aim gate opened from central 84% to the full frame minus a 4% edge,
  so ores are deliberately framed off-center (decays toward center only on
  retry, for recovery).
- Partial occlusion: 30% of shots accept a single visible face (per-shot
  `minVisibleSamples`), so the model sees half-hidden ores.
- Wider distance: viewpoints now 2–18 blocks out (was 2.5–13) for varied
  on-screen sizes incl. small/distant ores; capture box range bumped to 22.
- Graphics variety: Fast/Fancy + ambient-occlusion randomized per teleport
  spot; render distance randomized per session. All saved/restored.

### [x] 2. Parallelize the inference pipeline  — done 2026-06-13
**Problem:** `grab → infer → broadcast` was serial in one thread, so
FPS = 1/(capture_ms + inference_ms). Capture and GPU didn't overlap.
**What shipped:** `_FramePipe` ping-pong buffer + a dedicated capture thread in
`main.py`. The capture thread grabs frame N+1 while the inference thread runs
on N (stays at most one frame ahead, drops nothing stale), so throughput is
max(capture, inference). Verified: continuous stream, clean shutdown, 37.8 FPS
at full 1920×1080 with ~14 ms inference. The overlap pays off most when
inference dominates (bigger model / 1280px / FP16).

### [x] 3. Commit a real test suite  — done 2026-06-13
**What shipped:** `engine/tests/` (pytest, run `python -m pytest` from
`engine/`). 10 tests, ~1s, all temp-dir isolated — never touch real data:
- `test_collect_io.py`: finalize (split + yaml + pool consumed), empty-pool
  error, dedup keeps richest copy, merge appends+remaps classes, rebalance
  spreads rare classes into valid/test.
- `test_health.py`: box/empty counts, junk-class + too-few-boxes warnings,
  clean dataset → no warnings.
- `test_review.py`: engine review capture writes PNG + predictions, server
  uses latest frame, no-frame returns None.
- `conftest.py` fixtures build synthetic datasets/pools; `pytest.ini` config.
(GitHub Actions CI could come later; this protects the data pipeline now.)

---

## Medium impact

### [ ] 4. Pipeline crash resilience
**Problem:** if inference OOMs or weights are corrupt, the worker thread dies
but the WebSocket server stays up — the engine looks alive but never detects.
**Fix sketch:** catch per-frame errors, emit a "pipeline error" stat to the GUI
status bar, and auto-restart the worker (with backoff) instead of going zombie.

### [ ] 5. Per-class confidence thresholds
**Problem:** one global `--conf`; gold fires on lava at 0.4 while diamond is
solid at 0.4. **Fix sketch:** optional per-class threshold map (high for
false-positive-prone classes), applied after inference. Surface in the Engine
tab / a small config.

### [ ] 6. Address persistent class imbalance in training
**Problem:** deepslate_iron ~2265 boxes vs emerald ~6; training as-is leaves
rare classes weak no matter how much we farm. **Fix sketch:** class-balanced
sampling / loss weighting, or copy-paste augmentation for rare ores; report
per-class counts before training and warn on severe skew.

---

## Lower priority / cleanup

### [ ] 7. Validate coordinate alignment
Engine frame is assumed == GL viewport; no check that boxes actually line up
(monitor-fallback or DPI scaling breaks it). Add a sanity check / calibration
hint when frame size diverges from the game window.

### [ ] 8. Replace silent `except: pass` with logged catches
Many catches predate the logging infra and swallow errors silently; downgrade
to `log.debug/warning` so failures are traceable (mod WS, collector, GUI).

### [ ] 9. Overlay self-detection guard
Engine captures the window including the mod's own overlay/radar from the prior
frame; confirm the model never detects its own drawings (thin outlines are
probably safe, but verify, or have the engine capture before HUD if feasible).

---

## Done

- All spec phases 1–5 (see README roadmap).
- Active-learning review loop, hard-negative collection, dedup, stratified
  rebalance, dataset merge, multi-PC farms, app-wide logging, background
  threading for heavy GUI ops.
