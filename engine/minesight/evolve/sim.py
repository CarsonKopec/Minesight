"""Headless voxel simulator - trains the mining agent against block IDs, no
Minecraft, no rendering, no GLFW.

Since agent training uses ground-truth ore, the rendered client is pure
overhead: the agent's brain only ever asks "what block is here?". This module
answers that from a 3D array instead of a game window, so episodes run thousands
of times faster than real time, fully parallel, on any machine.

It reimplements the agent's *decision logic* - the A* pathfinder (same cost
model: mine/place/lava-near costs, dig + bridge moves) and the
explore->goto->mine behavior - over a deterministic procedural arena that mirrors
the in-game one (stone, scattered ore, lava, a water gap, a drop shaft). The
genome (param names/bounds in :mod:`genome`) is shared verbatim with the Java
agent, so a parameter vector tuned here drops straight into real Minecraft.

Honest scope: this is an *approximation* of MC physics. It faithfully tunes
path costs, ore prioritization, lava/drop safety, and dig-vs-detour tradeoffs.
The movement-*feel* params (waypoint_radius / stuck_window / stuck_min_move)
depend on real walking physics and have little effect here - tune those in a
short real-MC validation pass (sim-to-real).
"""
from __future__ import annotations

import heapq
import math
from dataclasses import dataclass

import numpy as np

# Block ids.
AIR, STONE, BEDROCK, ORE, LAVA, WATER, PLACED = range(7)

_SOLID = {STONE, BEDROCK, ORE, PLACED}
_MINEABLE = {STONE, ORE, PLACED}
_PASSABLE = {AIR, WATER}

# Tick costs (20 ticks = 1s), rough but in the right proportions.
WALK_TICKS = 5
SPRINT_TICKS = 3
PLACE_TICKS = 4
BREAK = {STONE: 6, PLACED: 6, ORE: 8}

# Fitness weights - identical to the Java harness so scores are comparable.
RARE_VALUE = 8.0
COMMON_VALUE = 1.0
DEATH_PENALTY = 10.0
SPEED_WEIGHT = 20.0
RARE_LABELS = ("diamond", "emerald")

# Hazard damage.
LAVA_STEP_DMG = 3.0       # per step taken adjacent to lava
FALL_PER = 3.0            # per block fallen beyond 3
MAX_HP = 20.0

# Arena dimensions (x, y, z).
W, H, D = 32, 18, 32
FLOOR = 8                 # local Y of the corridor floor (mid-height: room to climb + drop)
ORE_COUNT = 14
A_STAR_CAP = 60000


@dataclass
class Arena:
    grid: np.ndarray                      # (W, H, D) int8 block ids
    ores: list[tuple[str, int, int, int]] # (label, x, y, z)
    spawn: tuple[int, int, int]           # feet position


# ---------------------------------------------------------------------------
# Procedural arena (deterministic; mirrors the in-game ArenaManager layout).
# ---------------------------------------------------------------------------

def generate_arena(seed: int) -> Arena:
    rng = np.random.default_rng(seed)
    g = np.full((W, H, D), STONE, dtype=np.int8)
    # Bedrock shell.
    g[0, :, :] = g[W - 1, :, :] = BEDROCK
    g[:, :, 0] = g[:, :, D - 1] = BEDROCK
    g[:, 0, :] = g[:, H - 1, :] = BEDROCK

    midz = D // 2
    _corridor_x(g, 1, W - 2, midz)
    for bx in (W // 4, W // 2, 3 * W // 4):
        _corridor_z(g, bx, 1, D - 2)

    # Lava pockets on the corridor floor.
    for _ in range(3):
        lx = 4 + int(rng.integers(0, W - 8))
        g[lx, FLOOR, midz] = LAVA

    # Water gap across the corridor (bridge challenge).
    gapx = W // 2 + 3
    for dz in (-1, 0):
        z = midz + dz
        g[gapx, FLOOR, z] = AIR
        g[gapx, FLOOR - 1, z] = WATER
        g[gapx, FLOOR - 2, z] = WATER

    # Descending open stairwell to a diamond (depth challenge) - climbable both
    # ways (drop down, step back up), so the agent can't strand itself in a pit.
    # Open to the ceiling at each step gives the step-up move its headroom.
    ores: list[tuple[str, int, int, int]] = []
    sx = W // 4
    last_z, last_fy = 4, FLOOR - 1
    for i in range(5):
        z = 4 + i
        fy = FLOOR - 1 - i
        if fy < 1:
            break
        g[sx, fy, z] = STONE
        for yy in range(fy + 1, CORRIDOR_TOP + 1):
            g[sx, yy, z] = AIR
        last_z, last_fy = z, fy
    _place_ore(g, ores, sx, last_fy, last_z + 1, "diamond_ore")

    _scatter_ores(g, ores, rng)
    spawn = (2, FLOOR + 1, midz)
    _ensure_stand(g, 2, midz)
    return Arena(g, ores, spawn)


# Corridors are 4-tall: the pathfinder's step-up move needs headroom to "jump",
# so a flat 1-block step is only climbable in a tall-enough tunnel.
CORRIDOR_TOP = FLOOR + 4


def _corridor_x(g, x0, x1, z):
    for x in range(x0, x1 + 1):
        for dz in (-1, 0):
            for yy in range(FLOOR + 1, CORRIDOR_TOP + 1):
                g[x, yy, z + dz] = AIR


def _corridor_z(g, x, z0, z1):
    for z in range(z0, z1 + 1):
        for yy in range(FLOOR + 1, CORRIDOR_TOP + 1):
            g[x, yy, z] = AIR


def _ensure_stand(g, x, z):
    g[x, FLOOR, z] = STONE
    for yy in range(FLOOR + 1, CORRIDOR_TOP + 1):
        g[x, yy, z] = AIR


def _place_ore(g, ores, x, y, z, label):
    g[x, y, z] = ORE
    ores.append((label, x, y, z))


def _scatter_ores(g, ores, rng):
    candidates = []
    for x in range(2, W - 2):
        for y in range(1, H - 1):
            for z in range(2, D - 2):
                if g[x, y, z] == STONE and _near_air(g, x, y, z):
                    candidates.append((x, y, z))
    rng.shuffle(candidates)
    for x, y, z in candidates[:ORE_COUNT]:
        _place_ore(g, ores, x, y, z, _roll_ore(rng))


def _near_air(g, x, y, z) -> bool:
    for dx in range(-1, 2):
        for dy in range(-1, 2):
            for dz in range(-1, 2):
                if abs(dx) + abs(dy) + abs(dz) > 1:
                    continue
                nx, ny, nz = x + dx, y + dy, z + dz
                if 0 <= nx < W and 0 <= ny < H and 0 <= nz < D and g[nx, ny, nz] == AIR:
                    return True
    return False


def _roll_ore(rng) -> str:
    k = int(rng.integers(0, 100))
    if k < 40:
        return "coal_ore"
    if k < 65:
        return "iron_ore"
    if k < 80:
        return "copper_ore"
    if k < 88:
        return "redstone_ore"
    if k < 94:
        return "gold_ore"
    if k < 98:
        return "lapis_ore"
    return "diamond_ore" if rng.random() < 0.5 else "emerald_ore"


# ---------------------------------------------------------------------------
# Voxel world view (mutable; what the pathfinder + executor query).
# ---------------------------------------------------------------------------

class World:
    def __init__(self, grid: np.ndarray):
        self.g = grid

    def block(self, p) -> int:
        x, y, z = p
        if 0 <= x < W and 0 <= y < H and 0 <= z < D:
            return int(self.g[x, y, z])
        return BEDROCK

    def set(self, p, v):
        x, y, z = p
        self.g[x, y, z] = v

    def solid(self, p):
        return self.block(p) in _SOLID

    def mineable(self, p):
        return self.block(p) in _MINEABLE and not self.lava_adjacent(p)

    def passable(self, p):
        return self.block(p) in _PASSABLE

    def water(self, p):
        return self.block(p) == WATER

    def clear(self, p):
        return self.passable(p) and self.passable(_up(p))

    def hazard(self, p):
        return self.block(p) == LAVA or self.block(_up(p)) == LAVA

    def standable(self, feet):
        return (self.clear(feet) and self.solid(_down(feet))
                and not self.hazard(feet) and not self.water(_up(feet)))

    def lava_adjacent(self, p):
        x, y, z = p
        for dx, dy, dz in _SIX:
            if self.block((x + dx, y + dy, z + dz)) == LAVA:
                return True
        return False


_SIX = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))


def _up(p):
    return (p[0], p[1] + 1, p[2])


def _down(p):
    return (p[0], p[1] - 1, p[2])


# ---------------------------------------------------------------------------
# A* pathfinder - a faithful port of the Java PathFinder cost model.
# ---------------------------------------------------------------------------

class PathFinder:
    def __init__(self, world: World, params: dict):
        self.w = world
        self.mine_cost = params["mine_cost"]
        self.place_cost = params["place_cost"]
        self.lava_near = params["lava_near_cost"]
        self.max_drop = int(params["max_drop"])

    def find(self, start, goal):
        w = self.w
        if not w.standable(start):
            start = self._snap(start)
            if start is None:
                return None
        if not w.standable(goal):
            return None
        open_q = [(self._h(start, goal), 0, start)]
        gscore = {start: 0.0}
        came = {}
        seen = set()
        expansions = 0
        while open_q and expansions < A_STAR_CAP:
            _, _, cur = heapq.heappop(open_q)
            if cur == goal:
                return self._reconstruct(came, cur)
            if cur in seen:
                continue
            seen.add(cur)
            expansions += 1
            cg = gscore[cur]
            for nb in self._neighbors(cur):
                ng = cg + self._cost(cur, nb)
                if ng < gscore.get(nb, math.inf):
                    gscore[nb] = ng
                    came[nb] = cur
                    heapq.heappush(open_q, (ng + self._h(nb, goal), expansions, nb))
        return None

    def _neighbors(self, p):
        w = self.w
        x, y, z = p
        out = []
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1),
                       (1, 1), (1, -1), (-1, 1), (-1, -1)):
            diagonal = dx != 0 and dz != 0
            if diagonal and (not w.clear((x + dx, y, z)) or not w.clear((x, y, z + dz))):
                continue
            flat = (x + dx, y, z + dz)
            added = False
            if w.standable(flat):
                out.append(flat)
                added = True
            elif w.standable((x + dx, y + 1, z + dz)) and w.clear((x, y + 2, z)):
                out.append((x + dx, y + 1, z + dz))
                added = True
            elif w.clear(flat):
                for dy in range(1, self.max_drop + 1):
                    down = (x + dx, y - dy, z + dz)
                    if w.standable(down):
                        out.append(down)
                        added = True
                        break
                    if not w.passable(down) or w.water(down):
                        break
            if not added and not diagonal and w.solid(_down(flat)) and self._dig_through(flat):
                out.append(flat)
                added = True
            if (not added and not diagonal and w.clear(flat) and not w.hazard(flat)
                    and not w.water(_up(flat)) and not w.solid(_down(flat))
                    and w.solid(_down(p))):
                out.append(flat)
        if w.mineable(_down(p)) and w.solid((x, y - 2, z)):
            out.append(_down(p))
        return out

    def _dig_through(self, flat):
        any_solid = False
        for b in (flat, _up(flat)):
            if self.w.solid(b):
                if not self.w.mineable(b):
                    return False
                any_solid = True
        return any_solid

    def _cost(self, frm, to):
        diagonal = frm[0] != to[0] and frm[2] != to[2]
        c = 1.414 if diagonal else 1.0
        dy = to[1] - frm[1]
        if dy > 0:
            c += 1.0
        elif dy < 0:
            c += 0.4 * -dy
        if self.w.solid(to):
            c += self.mine_cost
        if self.w.solid(_up(to)):
            c += self.mine_cost
        if dy == 0 and not self.w.solid(_down(to)):
            c += self.place_cost
        if self.w.lava_adjacent(to):
            c += self.lava_near
        return c

    @staticmethod
    def _h(a, b):
        return math.dist(a, b)

    def _snap(self, p):
        for dy in range(0, -self.max_drop - 1, -1):
            c = (p[0], p[1] + dy, p[2])
            if self.w.standable(c):
                return c
        return None

    @staticmethod
    def _reconstruct(came, end):
        path = [end]
        while end in came:
            end = came[end]
            path.append(end)
        path.reverse()
        return path

    def standable_near(self, ore, frm, reach):
        """Nearest standable cell within `reach` of the ore (the agent stands
        here and mines toward it, digging through any stone in the way)."""
        r = max(1, int(math.ceil(reach)))
        best, best_d = None, math.inf
        ox, oy, oz = ore
        for dx in range(-r, r + 1):
            for dy in range(-1, r + 1):
                for dz in range(-r, r + 1):
                    c = (ox + dx, oy + dy, oz + dz)
                    if c == ore or math.dist(c, ore) > reach:
                        continue
                    if self.w.standable(c):
                        d = math.dist(c, frm)
                        if d < best_d:
                            best_d, best = d, c
        return best


# ---------------------------------------------------------------------------
# Episode: the explore->goto->mine behavior, scored.
# ---------------------------------------------------------------------------

@dataclass
class Episode:
    fitness: float = 0.0
    score: float = 0.0
    ores: int = 0
    deaths: int = 0
    ticks: int = 0
    cleared: bool = False


def run_episode(arena: Arena, params: dict, episode_ticks: int = 3600) -> Episode:
    world = World(arena.grid.copy())
    finder = PathFinder(world, params)
    reach = params["reach_dist"]
    rare_w = params["rare_weight"]
    remaining = {(x, y, z): label for (label, x, y, z) in arena.ores}
    total = len(remaining)

    pos = arena.spawn
    hp = MAX_HP
    t = 0
    score = 0.0
    mined = 0
    deaths = 0

    while remaining and t < episode_ticks:
        target = _pick_ore(pos, remaining, rare_w)
        goal = finder.standable_near(target, pos, reach)
        if goal is None:
            remaining.pop(target, None)        # nowhere to stand near it; skip
            continue
        path = finder.find(pos, goal)
        if path is None:
            remaining.pop(target, None)
            continue
        # Walk the route, digging/bridging as the cost model implies.
        dead = False
        for nxt in path[1:]:
            dt, dmg = _step(world, pos, nxt, goal, params)
            t += dt
            hp -= dmg
            pos = nxt
            if hp <= 0:
                deaths += 1
                dead = True
                break
            if t >= episode_ticks:
                break
        if dead or t >= episode_ticks:
            if dead:
                # respawn at the arena spawn, keep going on remaining ore
                pos = arena.spawn
                hp = MAX_HP
            continue
        # Mine through to the ore from the standing cell (vanilla "hold attack"):
        # clear a 2-tall tunnel along the line of sight, then the ore.
        for cell in _line_to(pos, target):
            for c in (cell, _up(cell)):
                if world.block(c) in _MINEABLE:
                    t += BREAK.get(world.block(c), 6)
                    world.set(c, AIR)
        t += BREAK[ORE]
        label = remaining.pop(target)
        world.set(target, AIR)
        score += RARE_VALUE if _is_rare(label) else COMMON_VALUE
        mined += 1
        # If we mined downward and dug out our own footing, fall to the new floor.
        fall = 0
        while fall < 8 and not world.standable(pos):
            pos = _down(pos)
            fall += 1
        if fall > 3:
            hp -= (fall - 3) * FALL_PER
            if hp <= 0:
                deaths += 1
                pos = arena.spawn
                hp = MAX_HP

    # "Cleared" means actually mined every ore - not emptied the list by skipping
    # unreachable ones. Only a true clear earns the finish-fast bonus.
    cleared = mined == total
    speed_bonus = SPEED_WEIGHT * (episode_ticks - t) / episode_ticks if cleared else 0.0
    fitness = score - DEATH_PENALTY * deaths + speed_bonus
    return Episode(fitness, score, mined, deaths, min(t, episode_ticks), cleared)


def _pick_ore(pos, remaining, rare_w):
    best, best_eff = None, math.inf
    disc = rare_w * rare_w
    for ore, label in remaining.items():
        d2 = (ore[0] - pos[0]) ** 2 + (ore[1] - pos[1]) ** 2 + (ore[2] - pos[2]) ** 2
        eff = d2 / disc if _is_rare(label) else d2
        if eff < best_eff:
            best_eff, best = eff, ore
    return best


def _step(world: World, frm, to, goal, params):
    """Advance one path cell: dig/bridge as needed, return (ticks, damage)."""
    ticks = 0
    to_mine = [c for c in (to, _up(to)) if world.solid(c)]
    plain = not to_mine
    for c in to_mine:
        ticks += BREAK.get(world.block(c), 6)
        world.set(c, AIR)
    # Bridge a floorless same-level step.
    if to[1] == frm[1] and not world.solid(_down(to)):
        world.set(_down(to), PLACED)
        ticks += PLACE_TICKS
        plain = False
    # Move time: sprint on long open runs.
    far = math.dist((to[0], 0, to[2]), (goal[0], 0, goal[2])) > params["sprint_dist"]
    ticks += SPRINT_TICKS if (plain and far) else WALK_TICKS
    # Damage: lava brushes + fall.
    dmg = 0.0
    if world.lava_adjacent(to) or world.lava_adjacent(frm):
        dmg += LAVA_STEP_DMG
    drop = frm[1] - to[1]
    if drop > 3:
        dmg += (drop - 3) * FALL_PER
    return ticks, dmg


def _line_to(frm, ore):
    """Cells strictly between the standing eye and the ore (Bresenham-ish)."""
    steps = max(abs(ore[0] - frm[0]), abs(ore[1] - frm[1]), abs(ore[2] - frm[2]))
    cells = []
    for i in range(1, steps):
        f = i / steps
        cells.append((round(frm[0] + (ore[0] - frm[0]) * f),
                      round(frm[1] + (ore[1] - frm[1]) * f),
                      round(frm[2] + (ore[2] - frm[2]) * f)))
    return cells


def _is_rare(label: str) -> bool:
    return any(r in label for r in RARE_LABELS)


def evaluate(params: dict, seed: int = 0, episode_ticks: int = 3600) -> dict:
    """Run one deterministic episode; returns fitness + metrics."""
    ep = run_episode(generate_arena(seed), params, episode_ticks)
    return {
        "fitness": ep.fitness,
        "score": ep.score,
        "ores": ep.ores,
        "deaths": ep.deaths,
        "ticks": ep.ticks,
        "cleared": ep.cleared,
    }
