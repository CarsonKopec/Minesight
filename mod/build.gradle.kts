// MineSight is a multi-module Forge 1.8.9 build:
//   :core       shared library mod (detection types, OreScanner, colors,
//               OverlayMode, shaded WebSocket client)  -> minesightcore.jar
//   :detection  live 2D overlay + engine link            -> minesightdetection.jar
//   :world      ore memory, 3D markers, radar            -> minesightworld.jar
//   :collector  automated dataset collection             -> minesightcollector.jar
//
// Each subproject is a self-contained loom/Forge project; the feature mods
// depend on :core and declare a Forge runtime dependency on it. Build all with
// `gradlew build`; run a flavor with e.g. `gradlew :world:runClient` (play) or
// `gradlew :collector:runClient` (farm).
