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
- The engine stores the latest state. Note: screen-to-world mapping (Phase 4)
  is done **in the mod**, which unprojects each detection through the live GL
  matrices and raycasts it into the real world — far more accurate than the
  spec's engine-side depth estimation, and it doesn't need this message.

## Mod/GUI → Engine: review capture (active learning)

```json
{"type": "review_capture"}
```

Sent by the mod (F9 keybind) or the GUI Engine tab. The engine writes the
current frame + the model's predictions to `engine/review/<stem>.png` +
`<stem>.json`; the GUI Review tab loads them for correction. The engine also
auto-captures uncertain frames (confidence 0.30–0.55) when run with
`--auto-review`. Review JSON shape:

```json
{"reason": "manual|auto", "frame_w": 1920, "frame_h": 1080, "ts": 0,
 "predictions": [{"label": "redstone_ore", "x": 540, "y": 320, "w": 40, "h": 40, "confidence": 0.42, "id": 7}]}
```

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

**Roles (2.0).** `collector_hello` may carry a `"role"`: `server` (the Folia
plugin — decides what/where to scan + how many to capture) or `client` (the
Fabric camera — decides how each frame is rendered). A 1.8.9 mod sends no role
and is treated as `legacy` (self-driving, gets the whole blob). On
`collect_start`/`collect_update` the GUI sends each connection only its fields:
server gets `target, radius, y_min/max, negative_ratio, hard_negative_ratio,
confuser_categories, avoid_revisits, smart_targeting, class_targets, classes,
output_dir`; client gets `gamma_min/max, fov_min/max, settle_ticks, classes`.
So **Apply live** retunes the scan on the plugin and the render on the camera,
each from the next shot.

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
  "hard_negative_ratio": 0.0,
  "confuser_categories": ["flowers", "foliage", "mushrooms", "redstone"],
  "settle_ticks": 40,
  "avoid_revisits": true,
  "smart_targeting": true,
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

`hard_negative_ratio` is the fraction of attempts that surface and photograph
confuser blocks as empty-label hard negatives — teaching the model not to fire
on colorful/cluttered non-ore. `confuser_categories` picks which categories
those shots may target (`flowers`, `foliage` = grass/leaves/vines, `mushrooms`,
`redstone` fixtures, `crops` = pumpkins/melons/cactus), so it isn't always
hunting flowers. Real ore that happens to be in frame is still labeled normally.

With `avoid_revisits`, the mod keeps a per-world file
(`minesight/visited_<world>.json` in the game directory) of every ore block
that appears in a saved capture and never aims at those again — across
sessions. `collect_clear_history` wipes it for the open world.

With `smart_targeting` (singleplayer/integrated-server worlds only), the mod
force-generates and scans chunks **on the server thread** to find cave-exposed
ore of the wanted classes, then teleports the camera straight to confirmed
positions instead of guessing — far fewer wasted hops. It falls back to random
teleporting while the scan queue is filling, so collection never stalls.

`classes` order defines the label indices the mod writes.

For clients on other machines (the GUI sets `"upload": true` for non-loopback
connections), captures are streamed instead of written to `output_dir`:

```json
{"type": "collect_image", "file": "collected_...png", "png": "<base64 PNG>", "labels": "0 0.5 0.5 0.1 0.1\n"}
```

The MineSight **2.0** Fabric client streams the same `collect_image` message
(`"client": "fabric-2.0"` in its `collector_hello`). There the Folia plugin
drives collection, not the GUI, so the client ignores `collect_start`/`stop`.
When no GUI session is armed, streamed images land in a dedicated `farm-stream`
dataset pool instead of being dropped.

Mod → GUI:

```json
{"type": "collect_progress", "saved": 42, "target": 300, "file": "collected_...png", "boxes": 3, "visited": 1234, "class_boxes": {"iron_ore": 80, "gold_ore": 12}, "thumb": "<base64 JPEG>"}
{"type": "collect_log", "message": "..."}
{"type": "collect_done", "saved": 300, "reason": "target|stopped|error", "message": "...", "class_counts": {"iron_ore": 120, "gold_ore": 35}}
```

## Notes

- The spec floated a `suggestion` message (engine → mod, e.g.
  `{"type": "suggestion", "action": "turn_left", "reason": "diamond detected"}`).
  MineSight instead generates action suggestions **in the mod** (Phase 5 radar):
  it has the ore memory's world positions and the player's facing, so it can
  point an arrow at the nearest valuable ore directly — no engine round-trip
  needed. No new wire message required.
- Unknown `type` values must be ignored by both sides (both implementations do).
