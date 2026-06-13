# MineSight 2.0 — Folia plugin + Fabric client (modern Minecraft)

Status: **in progress** on branch `minesight-2.0`. `main` stays on the working
Forge 1.8.9 build (preserved as branch `mc1.8.9` / tag `v0.13.1-mc1.8.9`).

## Why

The 1.8.9 collector runs an integrated server **inside each client** — every
client single-threads its own world generation, so scaling means more PCs. It
also can't generate deepslate/copper ore.

2.0 splits the work the way it should be:

- **One Folia server** (headless) does all the heavy world work — chunk
  generation + ore scanning — using **regionized multithreading**, so multiple
  spectators scattered across the map are ticked/generated on **different CPU
  cores**. This is the real performance win 1.8.9 can't give.
- **N Fabric clients** connect to it, render their view, capture labeled
  frames, and stream them out. Clients still must render (headless servers
  can't screenshot), but they no longer each run a world.
- Server + clients can share one machine: the multithreaded server + N client
  windows uses the cores far better than N single-threaded integrated servers.
- Modern Minecraft (**1.21.11**) brings deepslate + copper ore natively.

The plugin having full server authority also cleanly solves the teleport
problem the 1.8.9 remote-server idea hit: the **plugin** teleports the
spectators (no client-side movement, no anti-cheat).

## Components

```
┌─────────────────┐   control (WS/TCP)   ┌──────────────────────────┐
│ Control Panel   │ ───────────────────► │  Folia plugin (Java 21)  │
│ (Python GUI,    │ ◄─────────────────── │  MineSightFarm           │
│  mostly reused) │   progress           │  • regionized ore scan   │
└───────┬─────────┘                      │  • teleport spectators   │
        │ images (WS upload, reused)     │  • session orchestration │
        ▼                                └──────────┬───────────────┘
┌─────────────────┐   plugin-channel packets        │
│ Fabric client   │ ◄───────────────────────────────┘
│ (1.21.11)       │   "go here / capture now / ore AABBs"
│ • render        │ ──► capture framebuffer, project ground-truth
│ • capture+label │     boxes, stream PNG+labels to the GUI
└─────────────────┘
```

### Folia plugin — `plugin/`  (`MineSightFarm`)
- Java 21, Paper API `1.21.11-R0.1-SNAPSHOT`, `folia-supported: true`.
- Ports `ServerOreLocator`: force-generate + scan chunks for wanted ore, using
  Folia's **region scheduler** so scans run off the global tick.
- Teleports spectator players to ore (`Player.teleportAsync`, region-aware).
- Session control: start/stop/settings from the GUI; reports progress.
- Per-shot: positions a client, sends it the ore world-AABBs in view, tells it
  to capture.

### Fabric client mod — `client/`  (`minesight`)
- Java 21, Fabric Loader `0.19.3`, Fabric API `0.141.4+1.21.11`, Yarn
  `1.21.11+build.6`. **Client-only** (connects to the Folia server like any
  client).
- Detection overlay (Phase 1–2 port), world markers + radar (Phase 3–5 port) —
  reuses the *ideas*, rewritten on the 1.21 render pipeline (`GuiGraphics`,
  `RenderType`, `PoseStack` — the 1.8.9 `Tessellator`/`GlStateManager` code is
  gone).
- Collection: receives capture commands over the `minesight:farm` plugin
  channel, captures the framebuffer, projects the server-sent ore AABBs to
  screen, streams PNG + YOLO labels to the Control Panel (reuses the existing
  image-upload WebSocket).

### Control Panel — `engine/`  (Python, largely unchanged)
- YOLO engine is pixel-based → version-agnostic (retraining on 1.21 textures
  helps but the pipeline is the same).
- GUI collector tab now drives the **plugin** (session/settings) instead of
  each mod; still receives images from clients over the existing WS.

## Wire protocol

`minesight:farm` plugin channel (client ↔ plugin), length-prefixed binary or JSON:

Each message is a UTF type tag + a type-specific body via `DataInput`/`DataOutput`
(client `FarmProtocol`, mirrored plugin-side):

| Direction | Message | Payload |
|-----------|---------|---------|
| client→plugin | `hello` | UTF clientId |
| plugin→client | `pong` | UTF who (`MineSightFarm vX`) |
| plugin→client | `capture` | int shotId, bool hideHud, int n, then n×(UTF label, int min x/y/z, int max x/y/z) — ore AABBs in world coords |
| client→plugin | `captured` | int shotId, bool ok, int #boxesVisible |

GUI ↔ plugin: reuse the JSON-over-WebSocket control protocol (`PROTOCOL.md`),
plugin-side. Client → GUI image upload: reuse `collect_image` (`PROTOCOL.md`).

## Version matrix (pinned)

| Piece | Version |
|-------|---------|
| Minecraft | 1.21.11 |
| Folia / Paper API | 26.1.2 / `1.21.11-R0.1-SNAPSHOT` |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.4+1.21.11 |
| Yarn mappings | 1.21.11+build.6 |
| Fabric Loom | 1.17.11 (needs **Gradle 9.5.1** — client wrapper is set to it) |
| Paper plugin build | Gradle 8.14.3 (run-paper 3.0.2), Java 21 |
| Java | 21 |

## PoC status (branch `minesight-2.0`)

- ✅ **Folia plugin** (`plugin/`) builds — Paper/Folia 1.21.11 API + Java 21;
  registers the `minesight:farm` channel, replies `pong` to a client `hello`,
  uses the Folia global-region scheduler.
- ✅ **Fabric client** (`client/`) builds — MC 1.21.11 / Yarn / Fabric API; the
  `FarmPayload` custom-payload networking compiles against the real 1.21 API,
  sends `hello` on join, handles `pong`.
- ✅ **Live round-trip VERIFIED in-game** (2026-06-13): Folia 1.21.11 server +
  Fabric 1.21.11 client — hello/pong flows over `minesight:farm`. Risk #1 closed.
- ✅ **Regionized ore scanner** (`FoliaOreLocator` + `OreCatalog`) compiles
  against the real Folia API. Async chunk gen (`getChunkAtAsync(...,true)`),
  region-thread `ChunkSnapshot`, off-thread snapshot scan, nearest-first chunk
  order, bounded in-flight concurrency, thread-safe result queue. `OreCatalog`
  covers 1.21 ores incl. deepslate variants (→ same base label) and the new
  `copper_ore`, plus modern confuser categories (amethyst/sculk added).
- ✅ **In-game test command** `/minesightfarm scan <ore> [radius] | capture
  [count] | status | tp | stop` (alias `/msf`): drives the scanner off a
  global-region heartbeat and teleports the player (spectator, `teleportAsync`)
  to located ore.
- ✅ **Scan + teleport VERIFIED in-game** (2026-06-13): `/msf scan` fills the
  queue, `/msf tp` warps to a find. Risks #3/#4 closed.
- ✅ **Capture pipeline** built (both halves compile):
  - Plugin `CaptureSession` orchestrates teleport → settle → `capture` packet →
    await `captured` ack → next, over the queued ore, to a target image count.
  - Client `CaptureManager` settles a few ticks, grabs the framebuffer
    (`ScreenshotRecorder.takeScreenshot`), projects each ore world-AABB to a
    screen rect (`GroundTruthProjector`: camera pose from `getCameraPos`/
    yaw/pitch + a perspective matrix from the FOV option), occlusion-tests via
    `world.raycast`, writes `images/` + YOLO `labels/` (`DatasetWriter`, never
    saves an empty frame), and acks.
- ⏳ **Capture still to verify in-game** + calibrate box alignment (risk #2 /
  backlog #7): run `/msf scan` then `/msf capture <n>` and inspect the PNGs +
  labels under `.minecraft/minesight/captures/`. The FOV/near constants in
  `CaptureManager` are the alignment knobs.
- ✅ **Image upload to the GUI** wired — client `GuiUploader` (JDK `WebSocket`,
  no shaded lib) streams each saved frame as `collect_image` to the Control
  Panel's collector WS (default `ws://127.0.0.1:8766`, override
  `-Dminesight.guiUrl`). Best-effort + lazy reconnect; the durable local copy
  under `.minecraft/minesight/captures/` is always written too. The GUI accepts
  these outside a 1.8.9 session into a dedicated `farm-stream` dataset pool.
- ✅ **Phase 3 — detection overlay** ported to 1.21 (both compile):
  `EngineClient` (JDK `WebSocket`, default `ws://127.0.0.1:8765`, override
  `-Dminesight.engineUrl`) reconnects to the Python ML engine, streams `player`
  state each tick, and feeds `detections` into a `DetectionStore` (2 s
  staleness). `OverlayRenderer` draws the 2D boxes on `DrawContext` via Fabric
  `HudRenderCallback`, rescaling capture-frame px → scaled GUI coords. F8 cycles
  `OverlayMode`, F9 sends `review_capture`. Reuses the existing engine protocol
  unchanged, so the Python side needs no edits.
- ⏳ **Phase 3 remainder**: world markers + radar (the 3D/minimap parts) — they
  need screen↔world unprojection and are the next slice.
- ⏳ **Overlay still to verify in-game** (needs the engine running + a window
  the engine captures).

### Build / run
- Plugin: `cd plugin && ./gradlew build` → `plugin/build/libs/minesightfarm-2.0.0.jar` into the Folia `plugins/` folder.
  - `./gradlew runServer` (run-paper plugin) downloads the server, stages the
    built plugin into `plugins/`, and launches it under `plugin/run/`. First
    launch writes `run/eula.txt` — set `eula=true` (and `online-mode=false` in
    `run/server.properties` for a no-account dev server), then run again. Paper
    is used for the dev loop; the Folia scheduler APIs the plugin uses also run
    on Paper (single-threaded), so it's a faithful functional test. A real Folia
    jar is only needed to exercise true regionized multithreading.
  - Needs Gradle 8.14.3 (run-paper 3.0.2 requirement; plugin wrapper is set to it).
- Client: `cd client && ./gradlew build` → `client/build/libs/minesight-2.0.0.jar` into `.minecraft/mods/` (with Fabric Loader 0.19.3 + Fabric API). `./gradlew runClient` launches a dev client.
  - Multi-client: `-Pminesight.runDir=run-clientN` gives each client its own run
    dir; `-Pminesight.server=host[:port]` auto-joins on launch (quick play).
- **Control Panel → 🌾 Farm 2.0 tab** drives all of the above: Build plugin +
  client, Start/Stop the server (`runServer`), and Launch N Fabric clients
  (`runClient`, staggered, each in its own `run-clientN`, optional auto-join).

## Phasing

1. ✅ **PoC:** scaffold `plugin/` + `client/`; client↔plugin packet round-trip
   on 1.21.11; plugin scan/teleport on Folia. **Done + verified in-game.**
2. **Collection MVP (in progress):** plugin scan + teleport + capture-trigger ✅;
   client capture + ground-truth projection + local dataset write ✅ (both
   compile); ⏳ verify/calibrate captures in-game; ⏳ image upload to the GUI.
3. **Overlay/world port:** detection overlay ✅ (engine WS + HUD boxes + F8/F9);
   ⏳ world markers + radar (screen↔world) remain.
4. **Polish:** parity with 1.8.9 features (visited history, hard negatives,
   smart targeting, multi-client) on the new architecture.

## Open risks to validate in the PoC

1. ✅ NeoForge-free: **Fabric client ↔ Paper/Folia plugin custom packets** on
   1.21.11 — proven in-game.
2. ⏳ Can the connected client **project ground-truth ore boxes**? Plan
   implemented: plugin sends world AABBs, client projects with its camera pose +
   FOV-derived perspective matrix. **Still to calibrate against in-game captures**
   (the FOV/near constants in `CaptureManager`).
3. ✅ **Folia teleport throughput** — spectator `teleportAsync` works in-game.
4. ✅ Folia **async chunk load/generate + scan** — `FoliaOreLocator` finds ore
   in-game (scan counts climb, `/msf tp` lands on it).

## Repo layout (this branch)

```
plugin/    Folia/Paper plugin (new)
client/    Fabric client mod (new)
mod/       legacy Forge 1.8.9 (reference; removed once 2.0 reaches parity)
engine/    Python engine + Control Panel (shared, light changes)
docs/
```
