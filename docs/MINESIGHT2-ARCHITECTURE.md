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

| Direction | Message | Payload |
|-----------|---------|---------|
| client→plugin | `hello` | client id |
| plugin→client | `capture` | shotId, gamma/fov, ore AABBs (world coords + class) |
| client→plugin | `captured` | shotId, ok, #boxes |
| plugin→client | `session` | on/off, hideGUI, etc. |

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
| Paper plugin build | Gradle 8.10.2, Java 21 |
| Java | 21 |

## PoC status (branch `minesight-2.0`)

- ✅ **Folia plugin** (`plugin/`) builds — Paper/Folia 1.21.11 API + Java 21;
  registers the `minesight:farm` channel, replies `pong` to a client `hello`,
  uses the Folia global-region scheduler.
- ✅ **Fabric client** (`client/`) builds — MC 1.21.11 / Yarn / Fabric API; the
  `FarmPayload` custom-payload networking compiles against the real 1.21 API,
  sends `hello` on join, handles `pong`.
- ⏳ **Live round-trip** still to verify in-game: run a Folia 1.21.11 server with
  `minesightfarm-2.0.0.jar`, join with a Fabric 1.21.11 client carrying
  `minesight-2.0.0.jar` + Fabric API; the client chats "[MineSight] linked to
  MineSightFarm vX" if the packet path works. That confirms risk #1.
- ✅ **Regionized ore scanner** (`FoliaOreLocator` + `OreCatalog`) compiles
  against the real Folia API. Async chunk gen (`getChunkAtAsync(...,true)`),
  region-thread `ChunkSnapshot`, off-thread snapshot scan, nearest-first chunk
  order, bounded in-flight concurrency, thread-safe result queue. `OreCatalog`
  covers 1.21 ores incl. deepslate variants (→ same base label) and the new
  `copper_ore`, plus modern confuser categories (amethyst/sculk added).
- ✅ **In-game test command** `/minesightfarm scan <ore> [radius] | status | tp |
  stop` (alias `/msf`): drives the scanner off a global-region heartbeat and
  teleports the player (spectator, `teleportAsync`) to located ore — exercises
  risks #3/#4 without the Python GUI.
- ⏳ **Live scan/teleport** still to verify in-game (needs a running Folia world).

### Build / run
- Plugin: `cd plugin && ./gradlew build` → `plugin/build/libs/minesightfarm-2.0.0.jar` into the Folia `plugins/` folder.
- Client: `cd client && ./gradlew build` → `client/build/libs/minesight-2.0.0.jar` into `.minecraft/mods/` (with Fabric Loader 0.19.3 + Fabric API). `./gradlew runClient` launches a dev client.

## Phasing

1. **PoC (now):** scaffold `plugin/` + `client/`; prove a **client↔plugin
   packet round-trip** on 1.21.11 (the linchpin — if packets don't flow, the
   split changes). Plus confirm the plugin can scan/teleport on Folia.
2. **Collection MVP:** plugin scan + teleport + capture-trigger; client capture
   + ground-truth projection + image upload. Reuse the GUI collector.
3. **Overlay/world port:** detection overlay, markers, radar on 1.21.
4. **Polish:** parity with 1.8.9 features (visited history, hard negatives,
   smart targeting, multi-client) on the new architecture.

## Open risks to validate in the PoC

1. NeoForge-free: **Fabric client ↔ Paper/Folia plugin custom packets** on
   1.21.11 (1.20.5+ tightened custom-payload networking). ← prove first.
2. Can the connected client **project ground-truth ore boxes** from
   server-streamed chunks, or must the plugin send screen/world positions?
   (Plan: plugin sends world AABBs; client projects — it has the rendered
   chunks.)
3. **Folia teleport throughput** for rapid cross-region spectator moves
   (`teleportAsync`, region transfer cost).
4. Folia **async chunk load/generate + scan** ahead of the camera.

## Repo layout (this branch)

```
plugin/    Folia/Paper plugin (new)
client/    Fabric client mod (new)
mod/       legacy Forge 1.8.9 (reference; removed once 2.0 reaches parity)
engine/    Python engine + Control Panel (shared, light changes)
docs/
```
