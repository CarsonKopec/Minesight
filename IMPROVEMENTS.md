# MineSight — Improvement Backlog

Tracking quality/robustness work now that the spec roadmap (Phases 1–5) is
complete. Tick items as they land; keep the rationale so we remember *why*.

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done

---

## High impact

### [ ] 1. Diversify collected data (fix the "too clean" distribution)
**Problem:** the collector aims dead-center at every ore and only saves frames
that pass the aim gate, so the model learns "ore = a centered, well-framed,
fully-visible block." Real gameplay has ores off-center, partially occluded,
at varied distances, while moving. Train/play distribution mismatch silently
caps real-world accuracy — more *clean* data won't fix it.

**Fix sketch:**
- Randomize framing: offset the aim point so the ore lands off-center (not just
  small jitter — sometimes near a screen edge).
- Keep/encourage partial occlusion (loosen the visible-samples gate sometimes).
- Vary camera distance more widely.
- Vary graphics settings per session/shot: render distance, Fast/Fancy,
  (gamma already varied).
- Maybe a small "handheld" yaw/pitch drift to mimic looking around.
**Why first:** highest leverage on model quality, the project's weakest link.

### [ ] 2. Parallelize the inference pipeline
**Problem:** `grab → infer → broadcast` is serial in one thread, so
FPS = 1/(capture_ms + inference_ms). Capture and GPU don't overlap.
**Fix sketch:** producer/consumer — a capture thread fills a 1-slot latest-frame
buffer; the inference thread always works the newest frame. Raises throughput
and cuts overlay latency. (`engine/minesight/main.py`)

### [ ] 3. Commit a real test suite
**Problem:** all verification tests live in `%TEMP%` and vanish; zero committed
coverage over the data pipeline where a bug silently corrupts training data.
**Fix sketch:** `engine/tests/` with pytest covering `collect_io`
(finalize/merge/rebalance/dedup), `health.analyze`, `review` save, detector
tracking smoke. Wire a `pytest` run; optionally GitHub Actions.

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
