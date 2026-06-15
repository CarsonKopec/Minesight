"""Tests for the CMA-ES agent auto-tuner.

These prove the optimizer actually optimizes (the part we can verify without
Minecraft): it converges on standard test functions, the genome (de)coding is
sound, the simulated training loop recovers a hidden optimum, and a checkpoint
round-trips so a multi-day run can resume.
"""
from __future__ import annotations

import json

import numpy as np

from minesight.evolve import genome
from minesight.evolve.cmaes import CMAES
from minesight.evolve.trainer import Trainer


def _minimize(fn, x0, sigma0=0.5, gens=300, seed=0):
    es = CMAES(np.asarray(x0, dtype=float), sigma0, seed=seed)
    for _ in range(gens):
        xs = es.ask()
        costs = np.array([fn(x) for x in xs])
        es.tell(xs, costs)
        if es.converged():
            break
    return es


def test_cmaes_minimizes_sphere():
    # f(x) = sum x^2, optimum at 0. Start far away.
    es = _minimize(lambda x: float(np.sum(x ** 2)), x0=np.full(6, 3.0))
    assert np.linalg.norm(es.best_guess) < 1e-4


def test_cmaes_minimizes_ill_conditioned_ellipsoid():
    # Axis-scaled ellipsoid - only solvable well if the covariance adapts.
    n = 6
    coeffs = 10.0 ** (np.arange(n) / (n - 1) * 6)  # condition number 1e6

    def ellipsoid(x):
        return float(np.sum(coeffs * x ** 2))

    es = _minimize(ellipsoid, x0=np.full(n, 1.0), sigma0=0.3, gens=600)
    assert ellipsoid(es.best_guess) < 1e-6


def test_cmaes_minimizes_rosenbrock():
    def rosen(x):
        return float(np.sum(100 * (x[1:] - x[:-1] ** 2) ** 2 + (1 - x[:-1]) ** 2))

    es = _minimize(rosen, x0=np.zeros(4), sigma0=0.3, gens=800)
    assert np.allclose(es.best_guess, 1.0, atol=1e-2)


def test_genome_decode_in_bounds_and_int_rounding():
    # A normalized vector well outside [0,1] must clamp to the box.
    u = np.full(genome.DIM, 5.0)
    vals = genome.decode(u)
    for p in genome.PARAMS:
        assert p.lo <= vals[p.name] <= p.hi
        if p.is_int:
            assert float(vals[p.name]).is_integer()


def test_genome_defaults_roundtrip():
    vals = genome.decode(genome.default_normalized())
    for p in genome.PARAMS:
        assert vals[p.name] == p.default


def test_boundary_penalty():
    assert genome.boundary_penalty(np.full(genome.DIM, 0.5)) == 0.0
    assert genome.boundary_penalty(np.full(genome.DIM, 2.0)) > 0.0


def test_trainer_simulated_recovers_target(tmp_path):
    # A synthetic episode whose fitness peaks at a known param vector; the
    # trainer should drive the distribution mean onto it.
    rng = np.random.default_rng(7)
    target_u = rng.uniform(0.2, 0.8, size=genome.DIM)
    target_vals = genome.decode(target_u)
    spans = {p.name: (p.hi - p.lo) for p in genome.PARAMS}

    def fitness(values):
        err = sum(((values[n] - target_vals[n]) / spans[n]) ** 2 for n in values)
        return 100.0 - 10.0 * err

    t = Trainer(tmp_path, seed=1, sigma0=0.25)
    best = t.run_simulated(fitness, max_gens=200)
    # Recovered params should be close to the target (ints can only get within 0.5).
    for p in genome.PARAMS:
        tol = 0.6 if p.is_int else 0.06 * spans[p.name]
        assert abs(best.values[p.name] - target_vals[p.name]) <= tol


def test_checkpoint_roundtrip_and_resume(tmp_path):
    t = Trainer(tmp_path, seed=3)
    # Run a couple of generations against a trivial fitness, then checkpoint.
    t.run_simulated(lambda v: -float(v["mine_cost"]), max_gens=3)
    t.save_checkpoint()
    gen, mean = t.es.generation, t.es.best_guess.copy()

    t2 = Trainer(tmp_path, seed=99)
    t2.load_checkpoint()
    assert t2.es.generation == gen
    assert np.allclose(t2.es.best_guess, mean)


def test_file_ipc_protocol(tmp_path):
    # Drive one generation through the on-disk ask/tell contract by hand,
    # standing in for the in-game client.
    t = Trainer(tmp_path, seed=5)
    ids, _u, values = t._make_candidates()
    t._write_ask(ids, values)

    ask = json.loads(t.ask_path.read_text())
    assert {c["id"] for c in ask["candidates"]} == set(ids)
    # Every candidate's values are in-range and cover the full genome.
    for cand in ask["candidates"]:
        assert set(cand["values"]) == set(genome.NAMES)

    # The "client" appends a fitness line per candidate.
    with t.tell_path.open("w", encoding="utf-8") as fh:
        for k, cid in enumerate(ids):
            fh.write(json.dumps({"id": cid, "fitness": float(k)}) + "\n")

    results = t._read_tells()
    assert set(results) == set(ids)
    info = t._apply_results(results)
    assert info["gen"] == 1
    # Best fitness this generation was the last candidate (fitness == k).
    assert t.best.fitness == float(len(ids) - 1)
