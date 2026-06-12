# MineSight WebSocket Protocol

Transport: WebSocket, text frames, one JSON object per message.
The **Python engine is the server** (default `ws://127.0.0.1:8765`); the
**Forge mod is the client** and reconnects automatically.

## Engine → Mod: `detections`

Sent once per inference frame (uncapped, typically 60–100+ FPS on a 4060 Ti).

```json
{
  "type": "detections",
  "objects": [
    {
      "label": "diamond_ore",
      "x": 540.0,
      "y": 320.0,
      "w": 40.0,
      "h": 40.0,
      "confidence": 0.92,
      "id": 12
    }
  ],
  "frame_w": 1920,
  "frame_h": 1080,
  "ts": 1750000000000
}
```

- `x`, `y` — **box center** in capture-frame pixels (YOLO convention).
- `w`, `h` — box size in capture-frame pixels.
- `frame_w`, `frame_h` — capture frame size; the mod uses these to rescale into
  its GUI coordinates, so resolution mismatches stay aligned. *(Extension over
  the spec's minimal example.)*
- `id` — object ID, **persistent across frames** (Phase 2: ByteTrack, the
  modern successor of the spec's SORT, runs in the engine by default;
  `--no-track` falls back to throwaway sequential IDs).
- `ts` — engine wall-clock ms. The mod independently timestamps arrival and
  stops rendering frames older than 2 s (engine stopped/stalled), while holding
  the last known state between updates per the spec's frame-rate note.

## Mod → Engine: `player`

Sent every client tick (20 Hz) while in a world.

```json
{
  "type": "player",
  "x": 123.4,
  "y": 64.0,
  "z": -20.7,
  "yaw": 90.0,
  "pitch": -15.0,
  "fov": 90.0
}
```

- Position is the player entity position (doubles); `yaw`/`pitch` in degrees,
  `fov` is the FOV slider value.
- Phase 1 stores the latest state on the engine side; Phase 4
  (screen-to-world mapping) consumes it.

## GUI ↔ Engine: preview & stats (Control Panel only)

A client that sends `{"type": "subscribe_preview"}` (the Control Panel GUI)
additionally receives:

```json
{"type": "preview", "jpg": "<base64 JPEG>", "w": 960, "h": 540}
{"type": "stats", "fps": 87.3, "objects": 2, "mod_clients": 1, "window_found": true}
```

- `preview` is capped at ~12 FPS and downscaled to ≤960px wide.
- The mod never subscribes, so no image data crosses its connection (spec:
  "avoid sending images over WebSocket").
- `{"type": "unsubscribe_preview"}` stops the feed.

## GUI ↔ Mod: dataset collector (port 8766)

The Control Panel hosts a second WebSocket server on `ws://127.0.0.1:8766`; the
mod's collector module connects to it whenever the game is running and sends
`{"type": "collector_hello"}`. Multiple game clients may connect at once (each
in its own world); the GUI splits the total image target evenly and sends each
client its own `collect_start`. Filenames carry a per-instance token, so
parallel clients can write into the same pool safely.

GUI → mod:

```json
{
  "type": "collect_start",
  "output_dir": "C:\\...\\datasets\\collected-x\\pool",
  "target": 300, "radius": 300,
  "y_min": 5, "y_max": 62,
  "gamma_min": 0.0, "gamma_max": 1.5,
  "fov_min": 70, "fov_max": 110,
  "negative_ratio": 0.0,
  "settle_ticks": 40,
  "avoid_revisits": true,
  "class_targets": {"gold_ore": 200, "lapis_ore": 200},
  "classes": ["diamond_ore", "gold_ore", "iron_ore", "lapis_ore", "redstone_ore"]
}
{"type": "collect_stop"}
{"type": "collect_clear_history"}
{"type": "collect_update", "...": "any tunable collect_start field"}
```

`collect_update` retunes a *running* session live (radius, depth, gamma, FOV,
render wait, negatives, revisit-skipping, per-client target). `classes` and
`output_dir` are fixed for the session: label indices and file destinations
must not change mid-run.

With `avoid_revisits`, the mod keeps a per-world file
(`minesight/visited_<world>.json` in the game directory) of every ore block
that appears in a saved capture and never aims at those again — across
sessions. `collect_clear_history` wipes it for the open world.

`classes` order defines the label indices the mod writes.

Mod → GUI:

```json
{"type": "collect_progress", "saved": 42, "target": 300, "file": "collected_...png", "boxes": 3, "visited": 1234, "class_boxes": {"iron_ore": 80, "gold_ore": 12}, "thumb": "<base64 JPEG>"}
{"type": "collect_log", "message": "..."}
{"type": "collect_done", "saved": 300, "reason": "target|stopped|error", "message": "...", "class_counts": {"iron_ore": 120, "gold_ore": 35}}
```

## Planned message types (later phases)

- `suggestion` — engine → mod action hints (`{"type": "suggestion", "action": "turn_left", "reason": "diamond detected"}`).
- Unknown `type` values must be ignored by both sides (both implementations do).
