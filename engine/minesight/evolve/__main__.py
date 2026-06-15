"""CLI for the agent auto-tuner.

    python -m minesight.evolve                 # drive real episodes via the run dir
    python -m minesight.evolve --simulate      # converge on a synthetic fitness (no game)
    python -m minesight.evolve --show          # print the current best params
    python -m minesight.evolve --reset         # clear the run dir and start fresh
"""
from __future__ import annotations

import argparse
import json
import logging

import numpy as np

from . import genome
from .trainer import Trainer, default_run_dir


def _simulated_fitness(seed: int):
    """A synthetic 'episode': fitness peaks at a hidden target param vector.

    Mirrors the real loop's shape (decoded params in, scalar out, a little
    noise) so --simulate exercises the exact ask/decode/tell path.
    """
    rng = np.random.default_rng(seed)
    target = {p.name: rng.uniform(p.lo, p.hi) for p in genome.PARAMS}
    spans = {p.name: (p.hi - p.lo) for p in genome.PARAMS}

    def fitness(values: dict[str, float]) -> float:
        err = sum(((values[n] - target[n]) / spans[n]) ** 2 for n in values)
        return 100.0 - 10.0 * err + float(rng.normal(0, 0.05))

    fitness.target = target  # type: ignore[attr-defined]
    return fitness


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="minesight.evolve", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--dir", default=None, help="shared run dir (default ~/.minesight/train)")
    p.add_argument("--gens", type=int, default=40, help="generations to run")
    p.add_argument("--sigma0", type=float, default=0.2, help="initial step size (normalized)")
    p.add_argument("--popsize", type=int, default=None, help="lambda (default 4+3ln n)")
    p.add_argument("--seed", type=int, default=None, help="RNG seed")
    p.add_argument("--timeout", type=float, default=3600.0, help="per-episode wait, seconds")
    p.add_argument("--no-resume", action="store_true", help="ignore any existing checkpoint")
    p.add_argument("--simulate", action="store_true", help="optimize a synthetic fitness, no game")
    p.add_argument("--sim", action="store_true",
                   help="optimize against the headless voxel sim (block IDs, no Minecraft)")
    p.add_argument("--sim-seeds", type=int, default=4, help="arenas averaged per candidate (--sim)")
    p.add_argument("--show", action="store_true", help="print current best params and exit")
    p.add_argument("--reset", action="store_true", help="delete run-dir files and exit")
    a = p.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    run_dir = a.dir
    t = Trainer(run_dir, seed=a.seed, sigma0=a.sigma0, popsize=a.popsize)

    if a.reset:
        for f in ("ask.json", "tell.jsonl", "state.json", "status.json"):
            (t.dir / f).unlink(missing_ok=True)
        print(f"cleared {t.dir}")
        return 0

    if a.show:
        if t.state_path.exists():
            t.load_checkpoint()
            print(f"generation {t.es.generation}, best fitness {t.best.fitness:.3f}")
            print(json.dumps(t.best.values or genome.decode(t.es.best_guess), indent=2))
        else:
            print("no run yet; default params:")
            print(json.dumps(genome.decode(genome.default_normalized()), indent=2))
        return 0

    if a.simulate:
        fit = _simulated_fitness(a.seed or 0)
        best = t.run_simulated(fit, a.gens, log_every=max(1, a.gens // 20))
        print("\n-- simulated optimum --")
        print("best fitness:", round(best.fitness, 3))
        print("recovered params:", json.dumps(best.values, indent=2))
        return 0

    if a.sim:
        from .sim import evaluate
        seeds = tuple(range(a.sim_seeds))
        base = genome.decode(genome.default_normalized())
        base_fit = sum(evaluate(base, s)["fitness"] for s in seeds) / len(seeds)
        print(f"defaults baseline: {base_fit:.2f} (mean over {len(seeds)} arenas)")
        best = t.run_sim(a.gens, seeds=seeds, log_every=max(1, a.gens // 20))
        print("\n-- tuned params (voxel sim) --")
        print(f"best fitness: {best.fitness:.2f}  (vs defaults {base_fit:.2f})")
        print(json.dumps(best.values, indent=2))
        per = [evaluate(best.values, s) for s in seeds]
        cleared = sum(1 for r in per if r["cleared"])
        print(f"arenas cleared: {cleared}/{len(seeds)}; "
              f"avg ore {sum(r['ores'] for r in per) / len(seeds):.1f}, "
              f"deaths {sum(r['deaths'] for r in per) / len(seeds):.1f}")
        t.save_checkpoint()
        return 0

    print(f"run dir: {t.dir}")
    print("waiting for the in-game training harness to report episodes...")
    best = t.run(a.gens, episode_timeout=a.timeout, resume=not a.no_resume)
    print("\n-- best params --")
    print(json.dumps(best.values, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
