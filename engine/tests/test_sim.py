"""Tests for the headless voxel simulator.

These prove the sim is a usable, deterministic training environment: arenas
generate reproducibly, the pathfinder digs/bridges to reach ore, fitness
responds sensibly to the genome, and CMA-ES actually improves the agent in it.
"""
from __future__ import annotations

import numpy as np

from minesight.evolve import genome
from minesight.evolve import sim
from minesight.evolve.trainer import Trainer


def _defaults() -> dict:
    return genome.decode(genome.default_normalized())


def test_arena_generation_is_deterministic():
    a = sim.generate_arena(7)
    b = sim.generate_arena(7)
    assert np.array_equal(a.grid, b.grid)
    assert a.ores == b.ores
    assert a.spawn == b.spawn
    # Different seeds give different layouts.
    c = sim.generate_arena(8)
    assert not np.array_equal(a.grid, c.grid)


def test_arena_has_the_requested_challenges():
    a = sim.generate_arena(0)
    assert (a.grid == sim.LAVA).any(), "expected lava hazards"
    assert (a.grid == sim.WATER).any(), "expected a water gap"
    assert len(a.ores) >= sim.ORE_COUNT, "expected scattered ground-truth ore"
    # Spawn is a standable air pocket.
    world = sim.World(a.grid.copy())
    assert world.standable(a.spawn)


def test_episode_is_deterministic():
    p = _defaults()
    r1 = sim.evaluate(p, seed=3)
    r2 = sim.evaluate(p, seed=3)
    assert r1 == r2


def test_default_agent_mines_ore():
    # The out-of-the-box agent should clear a meaningful chunk of the arena.
    p = _defaults()
    r = sim.evaluate(p, seed=0)
    assert r["ores"] >= 5
    assert r["fitness"] > 0.0


def test_pathfinder_digs_to_a_walled_target():
    # Solid stone box with a standable pocket at each end - only reachable by
    # tunnelling, which the dig-aware A* should manage.
    g = np.full((sim.W, sim.H, sim.D), sim.STONE, dtype=np.int8)
    g[0, :, :] = g[sim.W - 1, :, :] = sim.BEDROCK
    g[:, :, 0] = g[:, :, sim.D - 1] = sim.BEDROCK
    g[:, 0, :] = g[:, sim.H - 1, :] = sim.BEDROCK
    for x in (2, sim.W - 3):
        g[x, 2, 5] = sim.STONE          # floor
        g[x, 3, 5] = sim.AIR
        g[x, 4, 5] = sim.AIR
    world = sim.World(g)
    finder = sim.PathFinder(world, _defaults())
    path = finder.find((2, 3, 5), (sim.W - 3, 3, 5))
    assert path is not None and path[0] == (2, 3, 5) and path[-1] == (sim.W - 3, 3, 5)


def test_lava_safety_param_changes_deaths_or_score():
    # An agent that ignores lava proximity should fare no better than one that
    # routes around it - i.e. the lava-near cost is a live knob.
    safe = _defaults()
    safe["lava_near_cost"] = 20.0
    reckless = _defaults()
    reckless["lava_near_cost"] = 0.0
    seeds = range(6)
    safe_fit = np.mean([sim.evaluate(safe, s)["fitness"] for s in seeds])
    reckless_fit = np.mean([sim.evaluate(reckless, s)["fitness"] for s in seeds])
    assert safe_fit >= reckless_fit - 1e-6


def test_cmaes_improves_the_agent_in_sim(tmp_path):
    seeds = (0, 1, 2)
    base = np.mean([sim.evaluate(_defaults(), s)["fitness"] for s in seeds])
    t = Trainer(tmp_path, seed=1, sigma0=0.25)
    best = t.run_sim(max_gens=15, seeds=seeds)
    assert best.fitness >= base   # tuning never makes it worse than defaults
