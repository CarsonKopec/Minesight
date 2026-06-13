# MineSight — Vision-Augmented Minecraft

Real-time AI perception for Minecraft, per [MineSight_Spec.pdf](MineSight_Spec.pdf).
A Python ML engine captures the Minecraft window, runs **YOLO26s** ore detection on
the GPU, and streams results over a WebSocket to a lightweight **Forge 1.8.9** client
mod that renders them as 2D overlays. The mod performs **no detection itself**.

```
┌─────────────────────┐   player state (JSON)   ┌──────────────────────┐
│  Forge 1.8.9 mod    │ ──────────────────────► │  Python ML engine    │
│  (renders overlays) │ ◄────────────────────── │  mss + YOLO26s + WS  │
└─────────────────────┘   detections (JSON)     └──────────────────────┘
        ws://127.0.0.1:8765  (mod = client, engine = server)
```

**Current status: Phase 1 — Basic Detection** (capture → inference → WebSocket → 2D boxes).
Later phases (tracking, memory, 3D mapping, radar) are scoped in the spec.

## Repository layout

| Path | What |
|------|------|
| `engine/` | Python ML engine (`minesight` package) + `train.py` |
| `engine/minesight_gui/` | PySide6 Control Panel (models, datasets, training, engine, mod) |
| `engine/tools/` | One-off dataset tooling (e.g. the v3 rebuild script) |
| `mod/` | Forge 1.8.9 client mod (modern Gradle via Essential's architectury-loom) |
| `MineSight-GUI.bat` | Double-click launcher for the Control Panel |
| `PROTOCOL.md` | WebSocket JSON protocol reference |
| `docs/spec-extract.txt` | Plain-text extraction of the spec PDF |

## Control Panel GUI

```powershell
cd engine
python -m minesight_gui        # or double-click MineSight-GUI.bat
```

- **Engine tab** — pick weights (trained runs listed with their mAP), start/stop
  the engine, watch logs, and see a live annotated preview of what the model
  detects, plus FPS / mod-connection status.
- **Models tab** — every training run with mAP50/mAP50-95, live metric curves
  (updates while training), "Use in engine" to deploy a model in one click.
- **Training tab** — pick dataset + base model, set epochs/batch/size, run and
  watch training logs live.
- **Datasets tab** — class-balance chart per split and automatic health
  warnings (junk class names, empty-label splits, classes with too few boxes).
- **Collector tab** — automated in-game dataset generation. With a singleplayer
  world open (mod ≥0.2.0), it commands the mod to: switch you to spectator,
  teleport around the world, find ores with line-of-sight, vary gamma/FOV/angle,
  and save perfectly-labeled screenshots (the game knows exactly where every ore
  is — boxes are projected from the actual block positions, occlusion-checked by
  raycast). Includes background "negative" frames, a live thumbnail of the last
  capture, and a *Finalize* step that splits the pool 80/10/10 and can merge it
  into an existing dataset by class name. Frames with zero visible ore boxes are
  never saved (unless you opt into background "negatives"), captured ores are
  remembered per world so veins are never photographed twice, and **multiple game
  clients can collect in parallel** (one world each — the target is split across
  them). Great for the rare stone-variant ores the Roboflow data lacks.
- **Multi-PC farms** — tick *Allow LAN clients* in the Collector tab (the
  status line shows the `ws://<ip>:8766` address) and other computers can join.
  Remote captures are streamed back over the WebSocket into the same pool;
  targets and class goals are split across every connected client, local or
  remote. On each remote PC, use the **Farm Agent** (below) — or any installed
  mod ≥0.8.1 with `-Dminesight.collector=ws://<host-ip>:8766` as a JVM arg, or
  a `minesight-collector.txt` file containing that address in the game folder.

### Farm Agent (remote machines)

The remote-PC counterpart to the Control Panel: a small window that launches
sandboxed game clients pointed at your host. Setup on the remote PC:

1. Copy/clone this repo, install Python 3.10+ and a JDK 17+
2. `pip install PySide6 psutil`
3. Double-click `MineSight-Farm.bat` (or `python -m minesight_gui.farm_agent`
   from `engine\`)
4. Enter the host address from your Collector tab, pick a client count, Launch

The first launch downloads Minecraft/Forge/JDK 8 via Gradle (several minutes);
afterwards it's instant. Clients appear in the host's Collector tab marked 🌐,
and the session is controlled entirely from the host.
- **Review tab — active learning (correct the model's mistakes)** — when the
  model is wrong while you play, press **F9** in-game (or *Capture for review*
  on the Engine tab) to snapshot that exact frame plus what it predicted. Or
  tick *Auto-flag uncertain* to have the engine auto-capture borderline
  (0.30–0.55 confidence) frames hands-free. In the Review tab you fix each one
  in a built-in annotation editor — delete false-positive boxes, drag/draw the
  correct ones, relabel via the class dropdown, or *All wrong → hard negative*
  for things like flowers — then *Save correction → dataset* writes it straight
  into the target dataset's train split. Mistake-corrections are the
  highest-value training data there is. Retrain and the model stops repeating
  that error.
- **Clients tab** — embeds running game windows as tabs inside the Control Panel
  (no floating windows). Farm clients launched from the Mod tab auto-embed;
  manually launched games embed on request and can be released back to the
  desktop at any time. Keep a window released if the detection engine should
  screen-capture it.
- **Mod tab** — build/install the jar, plus a **multi-client launcher**: pick a
  client count and world base name, and it spins up N sandboxed dev clients
  (`run-clientN/`), each auto-opening its own world (created on first launch).
- **Logs tab** — the Control Panel's own application log, live, with a Debug
  toggle. Full DEBUG detail is always written to `engine/logs/` (rotating
  files: `control-panel.log`, `engine.log`); the engine also logs there and
  takes `--debug` for per-frame capture/inference timings. The mod logs
  collector events through Forge's log4j.
- Status bar shows GPU utilization / VRAM / temperature.

Heavy dataset operations (rebalance, merge) run on a background thread so the
window never freezes, with a busy indicator while they work.

The preview/stats feed uses the same WebSocket as the mod (`subscribe_preview`
message); the mod never receives image data, per the spec's network guidance.

## 1. Python engine setup

Requires Python 3.10+ (a venv is created at `engine/.venv`) and an NVIDIA GPU
(target: RTX 4060 Ti).

```powershell
cd engine
py -3.13 -m venv .venv
.venv\Scripts\Activate.ps1

# CUDA PyTorch FIRST, otherwise ultralytics installs the CPU build
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126
pip install -r requirements.txt

# GPU verification (spec): both must succeed
python -c "import torch; print(torch.cuda.is_available())"          # True
python -c "import torch; print(torch.cuda.get_device_name(0))"      # RTX 4060 Ti
```

## 2. Train the ore model

Stock `yolo26s.pt` is pretrained on COCO (people, cars, …) — it will **not** detect
ores. Export your Roboflow dataset in **YOLO format** (includes `data.yaml`,
~2,524 images, 80/10/10 split per the spec), then:

```powershell
cd engine
python train.py --data path\to\dataset\data.yaml
```

- ~15–30 min for 100 epochs on a 4060 Ti. The script prints the exact path of
  the best weights when it finishes (ultralytics nests output under
  `runs/detect/minesight_runs/<name>/weights/best.pt`).
- Spec tip — also train the nano model and compare validation mAP:
  `python train.py --data ...\data.yaml --model yolo26n.pt --name yolo26n_ores`

## 3. Run the engine

```powershell
cd engine
python -m minesight --weights runs\detect\minesight_runs\yolo26s_ores\weights\best.pt
```

Useful flags:

- `--debug-view` — OpenCV window with drawn boxes; validates detection without the mod (press `q` to quit). With stock weights this is a good plumbing test.
- `--imgsz 1280` — higher-res inference (both 640 and 1280 are viable on a 4060 Ti).
- `--conf 0.5` — raise the confidence threshold if you get false positives.
- `--window-title "Minecraft"` — substring used to find the game window. If not found, the engine falls back to capturing the whole primary monitor.

There is no FPS cap — the engine runs at full GPU speed per the updated spec.

## 4. Build & install the mod

Gradle runs on your modern JDK (21 works); it auto-provisions the Java 8
toolchain it needs to compile 1.8.9 code.

```powershell
cd mod
.\gradlew.bat build
```

The finished jar is `mod/build/libs/minesight-0.1.0.jar` → drop it into your
Forge 1.8.9 `.minecraft/mods` folder. For development, `.\gradlew.bat runClient`
launches a dev client directly.

The mod connects to `ws://127.0.0.1:8765` and reconnects every 3 s forever, so
start order doesn't matter. Override the address with the JVM flag
`-Dminesight.backend=ws://host:port`.

## 5. Play

1. Start the engine (with your trained weights).
2. Launch Forge 1.8.9 with the mod installed and join a singleplayer world.
3. Engine log shows `Mod connected`; detected ores get colored boxes + labels in-game.

Color coding: diamond cyan, emerald green, gold yellow, redstone red, lapis blue,
iron tan, coal gray, copper orange (matched by substring of the class label).

## Troubleshooting

- **`CUDA available: False`** — you got CPU torch; redo step 1 (install torch from the cu126 index before `requirements.txt`).
- **Boxes misaligned** — make sure the engine found the game window (no "capturing monitor" warning in the log). Fullscreen-windowed or windowed mode both work; exclusive fullscreen can confuse window capture.
- **No boxes in-game but `--debug-view` shows detections** — check the engine log for `Mod connected`; if connected, see the comment in `OverlayRenderer` about the alternative render hook.
- **Multiplayer** — designed for singleplayer; servers may ban modified clients regardless of the overlay-only approach.

## Roadmap (from the spec)

- [x] **Phase 1 — Basic detection**: capture → YOLO26s → WebSocket → 2D boxes
- [x] **Phase 2 — Stability**: persistent object IDs via ByteTrack (the modern
  SORT successor, ultralytics-native; `--no-track` to disable), FP16 inference
  (`--half`, ~1.5–2× faster on RTX), overlay holds last state between updates
- [x] **Phase 3 — Memory & clustering**: per-world ore memory persisted to
  `minesight/memory_<world>.json`, survives looking away and restarts, forgets
  mined blocks; same-label nodes flood-filled into veins with one label each
- [x] **Phase 4 — 3D awareness**: detections are unprojected through the live
  GL matrices and raycast into the world, so markers anchor to the *exact*
  block the model saw (no noisy depth estimation — the mod has the real world).
  Through-wall world-space boxes + floating per-vein distance labels. **F8**
  cycles overlay modes (2D+3D / 3D / 2D / off); rare ores (diamond/emerald)
  ping when first discovered
- [ ] **Phase 3 — Intelligence**: memory system, vein clustering, prioritization
- [ ] **Phase 4 — 3D awareness**: screen-to-world mapping, world-space markers
- [ ] **Phase 5 — Advanced**: radar/minimap, prediction, action suggestions
