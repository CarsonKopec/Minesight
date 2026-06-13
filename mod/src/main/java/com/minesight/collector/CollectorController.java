package com.minesight.collector;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.world.WorldSettings;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Drives automated dataset collection: teleports the (spectator) player to
 * random underground spots, finds ore with line-of-sight, aims, and asks
 * CaptureHandler to grab a labeled frame. Commanded by the Control Panel GUI
 * via CollectorSocket; restores all player state when the session ends.
 */
public class CollectorController {
    private enum State {NEXT_TARGET, DATA_WAIT, FIND_CAVE, RENDER_WAIT, SCAN, SETTLE, AWAIT_CAPTURE, SAVING}

    private static final int SCAN_RADIUS = 20;
    private static final int CAPTURE_RADIUS = 16;
    /** Never teleport into the bedrock layer (y0-4) - you'd be encased. */
    private static final int MIN_SAFE_Y = 5;

    private static final Logger LOGGER = LogManager.getLogger("MineSight-Collector");

    private final Minecraft mc = Minecraft.getMinecraft();
    private final Random rng = new Random();
    private final DatasetWriter writer = new DatasetWriter();
    private final VisitedStore visited = new VisitedStore();

    private CollectorSocket socket;
    private CollectSession session;
    private State state;

    private int waitTicks;
    private int attempts;
    private BlockPos wanderCenter;
    private List<BlockPos> scanned = Collections.<BlockPos>emptyList();
    private Vec3 aimTarget;
    private BlockPos targetOre;
    private float jitterYaw;
    private float jitterPitch;
    private boolean negativeShot;
    private int settleTicks;
    private int settleNeeded;
    private int captureRetries;
    private static final int MAX_CAPTURE_RETRIES = 8;
    /**
     * Safety bound only: shooting at a spot ends naturally once every nearby
     * ore is marked visited, so a rich cave gets photographed exhaustively
     * before the collector teleports away.
     */
    private static final int MAX_SHOTS_PER_SPOT = 25;
    private static final int RELOCATE_AFTER_DRY_ATTEMPTS = 30;

    private int shotsAtSpot;
    private int dryAttempts;     // targets since the last successful save
    private String targetLabel;  // class of the aimed ore
    private boolean huntingConfusers;  // this attempt seeks surface hard negatives
    /** Saved BOXES per class this session - drives balance and class targets. */
    private final java.util.Map<String, Integer> sessionClassCounts =
            new java.util.HashMap<String, Integer>();
    /** 64x64 regions that repeatedly produced no caves/ores; avoided when
     *  rolling new teleport targets. Session-scoped. */
    private final java.util.Map<Long, Integer> barrenRegions =
            new java.util.HashMap<Long, Integer>();
    private static final int BARREN_THRESHOLD = 3;

    /** Set when a frame should be captured; consumed by CaptureHandler. */
    volatile boolean captureRequested;

    // Player/settings state to restore when the session ends.
    private WorldSettings.GameType prevGameType;
    private float prevGamma;
    private float prevFov;
    private boolean prevHideGui;
    private boolean prevPause;
    private boolean prevBobbing;
    private double startX, startY, startZ;

    public void setSocket(CollectorSocket socket) {
        this.socket = socket;
    }

    CollectSession session() {
        return session;
    }

    List<BlockPos> scannedOres() {
        return scanned;
    }

    /** Center of the ore the camera is currently aimed at, or null. */
    Vec3 aimTargetVec() {
        return aimTarget;
    }

    /** The ore block being photographed, or null (negative shot). */
    BlockPos targetOre() {
        return targetOre;
    }

    // --- commands from the GUI (client thread) -----------------------------

    public void onCommand(JsonObject msg) {
        String type = msg.has("type") ? msg.get("type").getAsString() : "";
        if ("collect_start".equals(type)) {
            start(CollectSession.fromJson(msg));
        } else if ("collect_stop".equals(type)) {
            finish("stopped");
        } else if ("collect_clear_history".equals(type)) {
            clearHistory();
        } else if ("collect_update".equals(type)) {
            if (session != null) {
                session.applyUpdate(msg);
                sendLog("Settings updated - applies from the next shot.");
            }
        }
    }

    private void clearHistory() {
        if (mc.getIntegratedServer() == null) {
            sendLog("Open a singleplayer world first to clear its history.");
            return;
        }
        visited.load(mc.getIntegratedServer().getFolderName());
        int n = visited.clear();
        sendLog("Cleared capture history for this world (" + n + " ores forgotten).");
    }

    private void start(CollectSession s) {
        if (session != null) {
            sendLog("A collection session is already running.");
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null || !mc.isSingleplayer()) {
            sendDone(0, "error", "Open a singleplayer world first.");
            return;
        }
        EntityPlayerMP sp = serverPlayer();
        if (sp == null) {
            sendDone(0, "error", "Could not access the integrated server player.");
            return;
        }
        if (s.classes.isEmpty()) {
            sendDone(0, "error", "No classes selected.");
            return;
        }
        if (!writer.prepare(s.outputDir, s.upload)) {
            sendDone(0, "error", "Cannot create output directory: " + s.outputDir);
            return;
        }
        session = s;
        prevGameType = sp.theItemInWorldManager.getGameType();
        prevGamma = mc.gameSettings.gammaSetting;
        prevFov = mc.gameSettings.fovSetting;
        prevHideGui = mc.gameSettings.hideGUI;
        prevPause = mc.gameSettings.pauseOnLostFocus;
        prevBobbing = mc.gameSettings.viewBobbing;
        startX = mc.thePlayer.posX;
        startY = mc.thePlayer.posY;
        startZ = mc.thePlayer.posZ;

        sp.setGameType(WorldSettings.GameType.SPECTATOR);
        mc.gameSettings.hideGUI = true;
        mc.gameSettings.pauseOnLostFocus = false;  // keep rendering while the GUI has focus
        mc.gameSettings.viewBobbing = false;

        wanderCenter = mc.thePlayer.getPosition();
        attempts = 0;
        dryAttempts = 0;
        shotsAtSpot = 0;
        sessionClassCounts.clear();
        barrenRegions.clear();
        controlEnvironment();
        sendLog("Weather cleared and daytime enforced for clean captures.");
        visited.load(mc.getIntegratedServer().getFolderName());
        state = State.NEXT_TARGET;
        LOGGER.info("Collection started: target={} classes={} upload={} hardNeg={} visited={}",
                s.target, s.classes, s.upload, s.hardNegativeRatio, visited.size());
        sendLog("Collection started: target " + s.target + " images, classes " + s.classes
                + (s.avoidRevisits ? " (skipping " + visited.size() + " previously captured ores)" : ""));
    }

    private void finish(String reason) {
        if (session == null) return;
        int saved = session.saved;
        EntityPlayerMP sp = serverPlayer();
        if (sp != null) {
            sp.setGameType(prevGameType);
            sp.setPositionAndUpdate(startX, startY, startZ);
        }
        mc.gameSettings.gammaSetting = prevGamma;
        mc.gameSettings.fovSetting = prevFov;
        mc.gameSettings.hideGUI = prevHideGui;
        mc.gameSettings.pauseOnLostFocus = prevPause;
        mc.gameSettings.viewBobbing = prevBobbing;
        session = null;
        captureRequested = false;
        visited.saveIfDirty();
        LOGGER.info("Collection finished: reason={} saved={} classBalance={}",
                reason, saved, sessionClassCounts);
        sendDone(saved, reason, null);
    }

    private EntityPlayerMP serverPlayer() {
        if (mc.getIntegratedServer() == null || mc.thePlayer == null) return null;
        return mc.getIntegratedServer().getConfigurationManager()
                .getPlayerByUsername(mc.thePlayer.getName());
    }

    // --- tick state machine -------------------------------------------------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null) return;
        if (mc.theWorld == null || mc.thePlayer == null) {
            finish("error");
            return;
        }
        // Menus (e.g. auto-pause) would end up inside the screenshots.
        if (mc.currentScreen != null) {
            mc.displayGuiScreen(null);
        }

        switch (state) {
            case NEXT_TARGET:
                nextTarget();
                break;
            case DATA_WAIT:
                // Only wait for world DATA here (cheap); the expensive render
                // settle happens after we know there's actually a cave to shoot.
                // Fresh terrain needs the server to GENERATE chunks first, so
                // give it a few seconds before giving up.
                waitTicks++;
                if (hasChunkData(mc.thePlayer.getPosition()) && waitTicks >= 5) {
                    // Now that real terrain heights are known, pull ore hunts
                    // down out of any open sky (ocean/mountain columns).
                    if (!huntingConfusers) {
                        clampBelowSurface();
                    }
                    state = State.FIND_CAVE;
                } else if (waitTicks > 80) {
                    state = State.NEXT_TARGET;
                }
                break;
            case FIND_CAVE:
                if (huntingConfusers) {
                    findSurface();
                } else {
                    findCave();
                }
                break;
            case RENDER_WAIT:
                waitTicks++;
                // The chunk RENDERER lags well behind world data after a long
                // teleport; capturing too early yields void frames. The frame
                // quality check catches stragglers, this wait avoids most.
                if (waitTicks >= session.settleTicks
                        && hasChunkData(mc.thePlayer.getPosition())) {
                    state = State.SCAN;
                } else if (waitTicks > session.settleTicks + 100) {
                    state = State.NEXT_TARGET;
                }
                break;
            case SCAN:
                scan();
                break;
            case SETTLE:
                if (aimTarget != null) {
                    aimAt(aimTarget);
                }
                settleTicks++;
                if (settleTicks >= settleNeeded) {
                    waitTicks = 0;
                    captureRequested = true;
                    state = State.AWAIT_CAPTURE;
                }
                break;
            case AWAIT_CAPTURE:
                waitTicks++;
                if (waitTicks > 60) {  // capture never happened (no render?)
                    captureRequested = false;
                    state = State.NEXT_TARGET;
                }
                break;
            case SAVING:
                break;
        }
    }

    private void nextTarget() {
        if (session.saved >= session.target) {
            finish("target");
            return;
        }
        attempts++;
        if (attempts > session.target * 40 + 400) {
            finish("error: gave up finding ores - try a bigger radius or different y range");
            return;
        }
        EntityPlayerMP sp = serverPlayer();
        if (sp == null) {
            finish("error");
            return;
        }
        shotsAtSpot = 0;
        dryAttempts++;
        controlEnvironment();
        // With visited-ore skipping, an area eventually taps out; migrate the
        // search center to fresh terrain instead of grinding it forever.
        if (dryAttempts > 0 && dryAttempts % RELOCATE_AFTER_DRY_ATTEMPTS == 0) {
            double angle = rng.nextDouble() * Math.PI * 2;
            int jump = session.radius + rng.nextInt(session.radius + 1);
            wanderCenter = wanderCenter.add(
                    (int) (Math.cos(angle) * jump), 0, (int) (Math.sin(angle) * jump));
            sendLog("Area looks tapped out - moving search center to "
                    + wanderCenter.getX() + ", " + wanderCenter.getZ());
        }
        // Avoid regions that already proved barren; reroll a few times.
        int x = 0;
        int z = 0;
        for (int roll = 0; roll < 10; roll++) {
            x = wanderCenter.getX() + rng.nextInt(session.radius * 2 + 1) - session.radius;
            z = wanderCenter.getZ() + rng.nextInt(session.radius * 2 + 1) - session.radius;
            Integer fails = barrenRegions.get(regionKey(x, z));
            if (fails == null || fails < BARREN_THRESHOLD) break;
        }
        // Some attempts go to the SURFACE to photograph confusers (flowers,
        // redstone fixtures) as hard negatives; the rest dig for ore.
        huntingConfusers = session.hardNegativeRatio > 0 && rng.nextDouble() < session.hardNegativeRatio;
        int y = huntingConfusers ? 160 : pickY();  // drop onto the surface from above
        sp.setPositionAndUpdate(x + 0.5, y, z + 0.5);
        waitTicks = 0;
        state = State.DATA_WAIT;
    }

    /**
     * If an ore-hunt teleport landed at or above the surface (open sky over an
     * ocean, a tall column, or just a high y_max), pull it back down into the
     * rock where caves and ore actually are - never below bedrock. Runs once
     * the chunk's real heights are known.
     */
    private void clampBelowSurface() {
        BlockPos pos = mc.thePlayer.getPosition();
        int surface = mc.theWorld.getHeight(pos).getY();  // first air above ground
        if (pos.getY() < surface - 1) {
            return;  // already underground
        }
        EntityPlayerMP sp = serverPlayer();
        if (sp == null) {
            return;
        }
        int hi = Math.min(Math.max(MIN_SAFE_Y, surface - 2), Math.min(254, session.yMax));
        int lo = Math.max(MIN_SAFE_Y, Math.min(session.yMin, hi));
        int y = lo + rng.nextInt(Math.max(1, hi - lo + 1));
        sp.setPositionAndUpdate(pos.getX() + 0.5, y, pos.getZ() + 0.5);
    }

    /**
     * For hard-negative attempts: drop from the spawn-in altitude down onto
     * the surface so confuser blocks (flowers etc.) are within scan range.
     */
    private void findSurface() {
        BlockPos pos = mc.thePlayer.getPosition();
        int top = mc.theWorld.getHeight(pos).getY();  // first air above ground
        if (top <= 1) {
            markBarren();
            state = State.NEXT_TARGET;
            return;
        }
        EntityPlayerMP sp = serverPlayer();
        if (sp == null) {
            finish("error");
            return;
        }
        // Float a few blocks above the surface for a clear downward view.
        sp.setPositionAndUpdate(pos.getX() + 0.5, top + 3, pos.getZ() + 0.5);
        waitTicks = 0;
        state = State.RENDER_WAIT;
    }

    private static long regionKey(int x, int z) {
        return (((long) (x >> 6)) << 32) ^ ((z >> 6) & 0xFFFFFFFFL);
    }

    /**
     * Whether the CLIENT actually has block data here. isBlockLoaded() lies on
     * the client (always true), and missing chunks are blank EmptyChunks that
     * read as all-air - which made never-generated terrain look like one giant
     * cave full of nothing.
     */
    private boolean hasChunkData(BlockPos pos) {
        return !mc.theWorld.getChunkFromBlockCoords(pos).isEmpty();
    }

    /**
     * How "satisfied" a class is - lower means hunt it harder. With per-class
     * targets this is the fill ratio (0 = nothing yet, 1 = done); classes that
     * are done or untargeted sort last. Without targets it's the raw count.
     */
    private double fillScore(String label) {
        Integer n = sessionClassCounts.get(label);
        int count = n == null ? 0 : n;
        if (!session.classTargets.isEmpty()) {
            Integer t = session.classTargets.get(label);
            if (t == null || t <= 0) return 1.0e9 + count;  // not requested
            if (count >= t) return 1.0e6 + count;           // already satisfied
            return count / (double) t;
        }
        return count;
    }

    private boolean classTargetsMet() {
        if (session.classTargets.isEmpty()) return false;
        for (java.util.Map.Entry<String, Integer> e : session.classTargets.entrySet()) {
            Integer n = sessionClassCounts.get(e.getKey());
            if ((n == null ? 0 : n) < e.getValue()) return false;
        }
        return true;
    }

    private void markBarren() {
        long key = regionKey(mc.thePlayer.getPosition().getX(), mc.thePlayer.getPosition().getZ());
        Integer n = barrenRegions.get(key);
        barrenRegions.put(key, n == null ? 1 : n + 1);
    }

    /**
     * Rain streaks ruin frames and night makes surface shots unusably dark;
     * brightness variety comes from the gamma randomization instead. Re-run
     * regularly because weather and time march on during long sessions.
     */
    private void controlEnvironment() {
        if (mc.getIntegratedServer() == null) return;
        net.minecraft.world.WorldServer world = mc.getIntegratedServer().worldServers[0];
        if (world == null) return;
        if (world.getWorldInfo().isRaining() || world.getWorldInfo().isThundering()) {
            world.getWorldInfo().setRaining(false);
            world.getWorldInfo().setThundering(false);
            world.getWorldInfo().setRainTime(20 * 60 * 60);     // dry for an hour
            world.getWorldInfo().setThunderTime(20 * 60 * 60);
        }
        long dayTime = world.getWorldTime() % 24000L;
        if (dayTime > 11000L) {  // dusk or later - skip to morning
            world.setWorldTime(world.getWorldTime() - dayTime + 24000L + 1000L);
        }
    }

    /**
     * Sample a teleport depth inside the spawn band of one of the session's
     * classes (e.g. diamond only below y16) instead of uniformly - far fewer
     * wasted hops when hunting deep ores.
     */
    private int pickY() {
        // The GUI allows modern-MC depths (-64..320); clamp to what a 1.8.9
        // world actually has, never into bedrock (y0-4).
        int userLo = Math.max(MIN_SAFE_Y, Math.min(254, session.yMin));
        int userHi = Math.max(userLo, Math.min(254, session.yMax));
        // Hunt the neediest class (fewest boxes / lowest target fill), so
        // teleport depths chase exactly the data the session still needs.
        String cls = session.classes.get(0);
        double best = Double.MAX_VALUE;
        for (String c : session.classes) {
            double score = fillScore(c) + rng.nextDouble() * 0.5;
            if (score < best) {
                best = score;
                cls = c;
            }
        }
        int lo = userLo;
        int hi = userHi;
        int[] band = OreScanner.yBand(cls);
        if (band != null) {
            lo = Math.max(lo, band[0]);
            hi = Math.min(hi, band[1]);
            if (lo > hi) {  // band outside the user's range - fall back
                lo = userLo;
                hi = userHi;
            }
        }
        return lo + rng.nextInt(Math.max(1, hi - lo + 1));
    }

    /**
     * Random teleport targets are almost always inside solid stone, and a
     * camera inside a block sees void. Relocate into a nearby cave air pocket
     * before scanning; if there is no cave around, skip this spot cheaply
     * (before paying the render settle).
     */
    private void findCave() {
        BlockPos center = mc.thePlayer.getPosition();
        BlockPos air = null;
        for (int t = 0; t < 250; t++) {
            int x = center.getX() + rng.nextInt(41) - 20;
            int y = Math.max(2, Math.min(250, center.getY() + rng.nextInt(41) - 20));
            int z = center.getZ() + rng.nextInt(41) - 20;
            BlockPos p = new BlockPos(x, y, z);
            if (!hasChunkData(p)) continue;
            if (mc.theWorld.isAirBlock(p) && mc.theWorld.isAirBlock(p.up())) {
                air = p;
                break;
            }
        }
        if (air == null) {
            markBarren();  // solid rock everywhere - remember and move on
            state = State.NEXT_TARGET;
            return;
        }
        EntityPlayerMP sp = serverPlayer();
        if (sp == null) {
            finish("error");
            return;
        }
        sp.setPositionAndUpdate(air.getX() + 0.5, air.getY(), air.getZ() + 0.5);
        waitTicks = 0;
        state = State.RENDER_WAIT;
    }

    private void scan() {
        captureRetries = 0;
        // Always scan ores so any real ore in frame still gets labeled, even
        // on a hard-negative (confuser) shot.
        scanned = OreScanner.scan(mc.theWorld, mc.thePlayer.getPosition(), SCAN_RADIUS, session.classSet());
        if (huntingConfusers) {
            scanConfusers();
            return;
        }
        if (scanned.isEmpty()) {
            markBarren();
            // We're in cave air (findCave guarantees it), so a background shot
            // from here shows real terrain, not the inside of a block.
            if (rng.nextDouble() < session.negativeRatio) {
                negativeShot = true;
                aimTarget = null;
                targetOre = null;
                targetLabel = null;
                mc.thePlayer.rotationYaw = rng.nextFloat() * 360f;
                mc.thePlayer.rotationPitch = -10f + rng.nextFloat() * 40f;
                beginSettle(5);
            } else {
                state = State.NEXT_TARGET;
            }
            return;
        }
        // Aim only at ores we haven't photographed yet; already-captured ones
        // still get labeled if they show up in a new shot's background.
        List<BlockPos> candidates = new java.util.ArrayList<BlockPos>();
        for (BlockPos p : scanned) {
            if (!session.avoidRevisits || !visited.contains(p)) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            state = State.NEXT_TARGET;
            return;
        }
        // Prefer the neediest class (fill ratio when targets are set, raw
        // count otherwise); random order within a class.
        final java.util.Map<BlockPos, Double> sortKeys = new java.util.HashMap<BlockPos, Double>();
        for (BlockPos p : candidates) {
            String label = OreScanner.labelFor(mc.theWorld.getBlockState(p).getBlock());
            sortKeys.put(p, fillScore(label) + rng.nextDouble() * 0.5);
        }
        Collections.sort(candidates, new java.util.Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos a, BlockPos b) {
                return Double.compare(sortKeys.get(a), sortKeys.get(b));
            }
        });
        int tries = Math.min(8, candidates.size());
        for (int i = 0; i < tries; i++) {
            BlockPos ore = candidates.get(i);
            Vec3 eye = findViewpoint(ore);
            if (eye != null) {
                EntityPlayerMP sp = serverPlayer();
                if (sp == null) {
                    finish("error");
                    return;
                }
                sp.setPositionAndUpdate(eye.xCoord, eye.yCoord - mc.thePlayer.getEyeHeight(), eye.zCoord);
                aimTarget = new Vec3(ore.getX() + 0.5, ore.getY() + 0.5, ore.getZ() + 0.5);
                targetOre = ore;
                targetLabel = OreScanner.labelFor(mc.theWorld.getBlockState(ore).getBlock());
                jitterYaw = (rng.nextFloat() - 0.5f) * 16f;
                jitterPitch = (rng.nextFloat() - 0.5f) * 12f;
                negativeShot = false;
                beginSettle(8);
                return;
            }
        }
        state = State.NEXT_TARGET;
    }

    /**
     * Hard-negative shot: frame a confuser block (flower, redstone fixture...)
     * and save it with no ore boxes - unless a real ore is genuinely in view,
     * which the capture handler will still label correctly.
     */
    private void scanConfusers() {
        List<BlockPos> confusers =
                OreScanner.scanConfusers(mc.theWorld, mc.thePlayer.getPosition(), SCAN_RADIUS);
        if (confusers.isEmpty()) {
            state = State.NEXT_TARGET;  // bare biome (desert/ocean) - reroll
            return;
        }
        Collections.shuffle(confusers, rng);
        int tries = Math.min(8, confusers.size());
        for (int i = 0; i < tries; i++) {
            BlockPos target = confusers.get(i);
            Vec3 eye = findViewpoint(target);
            if (eye != null) {
                EntityPlayerMP sp = serverPlayer();
                if (sp == null) {
                    finish("error");
                    return;
                }
                sp.setPositionAndUpdate(eye.xCoord, eye.yCoord - mc.thePlayer.getEyeHeight(), eye.zCoord);
                aimTarget = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
                targetOre = null;
                targetLabel = null;
                jitterYaw = (rng.nextFloat() - 0.5f) * 16f;
                jitterPitch = (rng.nextFloat() - 0.5f) * 12f;
                negativeShot = true;  // empty label is the whole point
                beginSettle(8);
                return;
            }
        }
        state = State.NEXT_TARGET;
    }

    private void beginSettle(int ticks) {
        mc.gameSettings.gammaSetting =
                session.gammaMin + rng.nextFloat() * (session.gammaMax - session.gammaMin);
        mc.gameSettings.fovSetting =
                session.fovMin + rng.nextInt(Math.max(1, session.fovMax - session.fovMin + 1));
        settleTicks = 0;
        settleNeeded = ticks;
        state = State.SETTLE;
    }

    /** A nearby air position whose line of sight reaches the ore. Returns the eye position. */
    private Vec3 findViewpoint(BlockPos ore) {
        Vec3 oreCenter = new Vec3(ore.getX() + 0.5, ore.getY() + 0.5, ore.getZ() + 0.5);
        for (int t = 0; t < 40; t++) {
            double dx = rng.nextGaussian();
            double dy = rng.nextGaussian() * 0.5;
            double dz = rng.nextGaussian();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.01) continue;
            double dist = 2.5 + rng.nextDouble() * 10.5;
            Vec3 eye = oreCenter.addVector(dx / len * dist, dy / len * dist, dz / len * dist);
            BlockPos feet = new BlockPos(eye.xCoord, eye.yCoord - mc.thePlayer.getEyeHeight(), eye.zCoord);
            if (!hasChunkData(feet)) continue;
            if (!mc.theWorld.isAirBlock(feet) || !mc.theWorld.isAirBlock(feet.up())) continue;
            // The eye itself can sit a block above the head block - a camera
            // clipped into the ceiling sees void.
            if (!mc.theWorld.isAirBlock(new BlockPos(eye.xCoord, eye.yCoord, eye.zCoord))) continue;
            if (RaycastUtil.firstBlockHit(mc.theWorld, eye, oreCenter, ore)) {
                return eye;
            }
        }
        return null;
    }

    private void aimAt(Vec3 target) {
        double ex = mc.thePlayer.posX;
        double ey = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double ez = mc.thePlayer.posZ;
        double dx = target.xCoord - ex;
        double dy = target.yCoord - ey;
        double dz = target.zCoord - ez;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        mc.thePlayer.rotationYaw = yaw + jitterYaw;
        mc.thePlayer.rotationPitch = MathHelper.clamp_float(pitch + jitterPitch, -90f, 90f);
    }

    // --- capture results (client/render thread) -----------------------------

    boolean wantsNegative() {
        return negativeShot;
    }

    /**
     * The frame failed the quality check (void/black/uniform). Wait a few more
     * ticks with a fresh random gamma and try again; the renderer may just
     * need more time. Give up on this spot after a few attempts.
     */
    void onCaptureRejected(String reason) {
        captureRequested = false;
        if (session == null) return;
        captureRetries++;
        if (captureRetries > MAX_CAPTURE_RETRIES) {
            sendLog("Skipped a spot after " + MAX_CAPTURE_RETRIES + " bad frames (" + reason + ")");
            state = State.NEXT_TARGET;
        } else {
            // The jitter is rolled once per shot; an unlucky draw can push the
            // target outside the aim gate on EVERY retry ("staring" at an ore
            // without ever shooting). Decay toward dead-center so retries
            // converge on a guaranteed-valid aim.
            jitterYaw *= 0.4f;
            jitterPitch *= 0.4f;
            beginSettle(5);
        }
    }

    void onCaptured(ByteBuffer rgba, int width, int height, List<float[]> boxes,
                    final List<BlockPos> boxedOres, final List<String> boxedLabels) {
        captureRequested = false;
        if (session == null) return;
        if (boxes.isEmpty() && !negativeShot) {
            // Aimed at an ore but nothing ended up visible - not worth saving.
            state = State.NEXT_TARGET;
            return;
        }
        state = State.SAVING;
        if (session.upload) {
            // Remote client: the image travels to the Control Panel over WS.
            writer.uploadAsync(rgba, width, height, boxes, new DatasetWriter.UploadCallback() {
                @Override
                public void onReady(final String fileName, final String pngB64, final String labels,
                                    final int boxCount, final String thumbB64) {
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            if (session == null) return;
                            JsonObject img = new JsonObject();
                            img.addProperty("type", "collect_image");
                            img.addProperty("file", fileName);
                            img.addProperty("png", pngB64);
                            img.addProperty("labels", labels);
                            if (socket != null) socket.send(img);
                            afterSaved(fileName, boxCount, thumbB64, boxedOres, boxedLabels);
                        }
                    });
                }

                @Override
                public void onError(final String message) {
                    onSaveError(message);
                }
            });
        } else {
            writer.saveAsync(rgba, width, height, boxes, new DatasetWriter.Callback() {
                @Override
                public void onSaved(final String fileName, final int boxCount, final String thumbB64) {
                    mc.addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            afterSaved(fileName, boxCount, thumbB64, boxedOres, boxedLabels);
                        }
                    });
                }

                @Override
                public void onError(final String message) {
                    onSaveError(message);
                }
            });
        }
    }

    private void afterSaved(String fileName, int boxCount, String thumbB64,
                            List<BlockPos> boxedOres, List<String> boxedLabels) {
        if (session == null) return;
        session.saved++;
        dryAttempts = 0;
        shotsAtSpot++;
        // Count every saved BOX per class (a 6-block vein = 6).
        for (String label : boxedLabels) {
            Integer c = sessionClassCounts.get(label);
            sessionClassCounts.put(label, c == null ? 1 : c + 1);
        }
        // Everything boxed in this image counts as photographed.
        for (BlockPos p : boxedOres) {
            visited.add(p);
        }
        if (session.saved % 20 == 0) {
            visited.saveIfDirty();
        }
        sendProgress(fileName, boxCount, thumbB64);
        if (session.saved >= session.target || classTargetsMet()) {
            finish("target");
        } else if (shotsAtSpot < MAX_SHOTS_PER_SPOT) {
            // This cave system probably has more unvisited ore;
            // rescan from here and amortize the teleport+settle.
            state = State.SCAN;
        } else {
            state = State.NEXT_TARGET;
        }
    }

    private void onSaveError(final String message) {
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                sendLog("Save failed: " + message);
                if (session != null) state = State.NEXT_TARGET;
            }
        });
    }

    // --- messages to the GUI -------------------------------------------------

    private void sendProgress(String fileName, int boxes, String thumbB64) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "collect_progress");
        o.addProperty("saved", session.saved);
        o.addProperty("target", session.target);
        o.addProperty("file", fileName);
        o.addProperty("boxes", boxes);
        o.addProperty("visited", visited.size());
        JsonObject classBoxes = new JsonObject();
        for (java.util.Map.Entry<String, Integer> e : sessionClassCounts.entrySet()) {
            classBoxes.addProperty(e.getKey(), e.getValue());
        }
        o.add("class_boxes", classBoxes);
        o.addProperty("thumb", thumbB64);
        if (socket != null) socket.send(o);
    }

    private void sendLog(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "collect_log");
        o.addProperty("message", message);
        if (socket != null) socket.send(o);
    }

    private void sendDone(int saved, String reason, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "collect_done");
        o.addProperty("saved", saved);
        o.addProperty("reason", reason);
        if (message != null) o.addProperty("message", message);
        JsonObject counts = new JsonObject();
        for (java.util.Map.Entry<String, Integer> e : sessionClassCounts.entrySet()) {
            counts.addProperty(e.getKey(), e.getValue());
        }
        o.add("class_counts", counts);
        if (socket != null) socket.send(o);
    }
}
