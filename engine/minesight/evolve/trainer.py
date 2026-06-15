"""Drives CMA-ES over the agent genome, talking to the game through a shared
run directory (file IPC) so the optimizer and Minecraft are independent,
crash-resilient processes.

Protocol (all files live in the run dir, default ``~/.minesight/train``):

  ask.json    written by us  - the current generation's candidate params:
                {"gen": 3, "candidates": [{"id": "g3_0", "values": {...}}, ...]}
  tell.jsonl  appended by the client - one result per line:
                {"id": "g3_0", "fitness": 12.5, "ores": 4, "deaths": 0, ...}
  state.json  our checkpoint - CMA-ES state + the pending generation, so a
              restart resumes mid-run without losing evaluated episodes.
  status.json small heartbeat for the GUI (best so far, generation, sigma).

The client picks any candidate in ask.json that has no tell yet, runs one
episode, appends its fitness, and moves on. We wait until every candidate is
answered, feed the costs to CMA-ES, checkpoint, and ask the next generation.
"""
from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import numpy as np

from .cmaes import CMAES
from . import genome

log = logging.getLogger("minesight.evolve")

# Out-of-bounds candidates are evaluated clamped, then penalized so the search
# learns to stay feasible. Big relative to a typical fitness swing.
PENALTY_WEIGHT = 1000.0


def default_run_dir() -> Path:
    return Path.home() / ".minesight" / "train"


@dataclass
class Best:
    fitness: float = -float("inf")
    values: dict[str, float] = field(default_factory=dict)
    u: list[float] = field(default_factory=list)


class Trainer:
    def __init__(
        self,
        run_dir: Path | None = None,
        *,
        seed: int | None = None,
        sigma0: float = 0.2,
        popsize: int | None = None,
        penalty_weight: float = PENALTY_WEIGHT,
    ) -> None:
        self.dir = Path(run_dir) if run_dir else default_run_dir()
        self.dir.mkdir(parents=True, exist_ok=True)
        self.penalty_weight = penalty_weight
        self.es = CMAES(genome.default_normalized(), sigma0, popsize=popsize, seed=seed)
        self.best = Best()
        self._pending_u: np.ndarray | None = None
        self._pending_ids: list[str] = []

    # -- files -------------------------------------------------------------

    @property
    def ask_path(self) -> Path:
        return self.dir / "ask.json"

    @property
    def tell_path(self) -> Path:
        return self.dir / "tell.jsonl"

    @property
    def state_path(self) -> Path:
        return self.dir / "state.json"

    @property
    def status_path(self) -> Path:
        return self.dir / "status.json"

    # -- candidate <-> file ------------------------------------------------

    def _make_candidates(self) -> tuple[list[str], np.ndarray, list[dict]]:
        u = self.es.ask()
        gen = self.es.generation
        ids = [f"g{gen}_{k}" for k in range(u.shape[0])]
        values = [genome.decode(u[k]) for k in range(u.shape[0])]
        self._pending_u = u
        self._pending_ids = ids
        return ids, u, values

    def _write_ask(self, ids: list[str], values: list[dict]) -> None:
        payload = {
            "gen": self.es.generation,
            "candidates": [{"id": i, "values": v} for i, v in zip(ids, values)],
        }
        _atomic_write_json(self.ask_path, payload)

    def _read_tells(self) -> dict[str, dict]:
        """Parse tell.jsonl into {id: result}. Tolerates partial/garbled lines."""
        out: dict[str, dict] = {}
        if not self.tell_path.exists():
            return out
        for line in self.tell_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(rec, dict) and "id" in rec:
                out[str(rec["id"])] = rec
        return out

    # -- one generation ----------------------------------------------------

    def _apply_results(self, results: dict[str, dict]) -> dict:
        """Turn the generation's fitness reports into a CMA-ES update."""
        assert self._pending_u is not None
        u = self._pending_u
        costs = np.empty(len(self._pending_ids))
        fits = []
        for k, cid in enumerate(self._pending_ids):
            fit = float(results[cid].get("fitness", -1e9))
            fits.append(fit)
            costs[k] = -fit + self.penalty_weight * genome.boundary_penalty(u[k])
            if fit > self.best.fitness:
                self.best = Best(fit, genome.decode(u[k]), u[k].tolist())
        self.es.tell(u, costs)
        self._pending_u = None
        self._pending_ids = []
        self._write_best()
        return {
            "gen": self.es.generation,
            "best": self.best.fitness,
            "mean_fit": float(np.mean(fits)),
            "max_fit": float(np.max(fits)),
            "sigma": self.es.sigma,
        }

    # -- run modes ---------------------------------------------------------

    def run_simulated(
        self,
        fitness_fn: Callable[[dict[str, float]], float],
        max_gens: int,
        *,
        log_every: int = 0,
    ) -> Best:
        """Optimize against an in-memory fitness function (tests + dry runs).

        ``fitness_fn`` maps a decoded {name: value} param map to a scalar
        fitness (higher is better) - it stands in for a real episode.
        """
        for _ in range(max_gens):
            ids, u, values = self._make_candidates()
            results = {ids[k]: {"id": ids[k], "fitness": fitness_fn(values[k])}
                       for k in range(len(ids))}
            info = self._apply_results(results)
            if log_every and info["gen"] % log_every == 0:
                log.info("gen %d  best=%.4f  sigma=%.4g", info["gen"],
                         info["best"], info["sigma"])
            if self.es.converged():
                break
        return self.best

    def run_sim(
        self,
        max_gens: int,
        *,
        seeds: tuple[int, ...] = (0, 1, 2, 3),
        episode_ticks: int = 3600,
        log_every: int = 0,
    ) -> Best:
        """Optimize against the headless voxel simulator - no Minecraft.

        Each candidate is scored as the mean fitness over several arena
        ``seeds`` (different layouts) so the genome generalizes instead of
        overfitting one arena. Fast enough to run thousands of episodes inline.
        """
        from .sim import evaluate

        def fitness(values: dict[str, float]) -> float:
            return float(np.mean([evaluate(values, s, episode_ticks)["fitness"] for s in seeds]))

        return self.run_simulated(fitness, max_gens, log_every=log_every)

    def run(
        self,
        max_gens: int,
        *,
        poll_interval: float = 2.0,
        episode_timeout: float = 3600.0,
        resume: bool = True,
    ) -> Best:
        """The real loop: drive the game through the run dir, generation by generation."""
        if resume and self.state_path.exists():
            self.load_checkpoint()
            log.info("resumed at generation %d (best %.3f)", self.es.generation, self.best.fitness)

        start_gen = self.es.generation
        while self.es.generation < start_gen + max_gens:
            # Re-issue the pending generation on resume, else ask a fresh one.
            if self._pending_u is None:
                ids, _u, values = self._make_candidates()
                self._write_ask(ids, values)
                self._write_status(waiting=len(ids))
                log.info("generation %d: asked %d candidates", self.es.generation, len(ids))
            else:
                ids = self._pending_ids

            results = self._await_generation(ids, poll_interval, episode_timeout)
            info = self._apply_results(results)
            self.save_checkpoint()
            self._write_status(waiting=0, info=info)
            log.info("generation %d done: best=%.3f mean=%.3f max=%.3f sigma=%.4g",
                     info["gen"], info["best"], info["mean_fit"], info["max_fit"], info["sigma"])
            if self.es.converged():
                log.info("search converged - stopping")
                break
        return self.best

    def _await_generation(self, ids: list[str], poll: float, timeout: float) -> dict[str, dict]:
        """Block until every candidate id has a tell (or time out and fail loudly)."""
        deadline = time.monotonic() + timeout
        wanted = set(ids)
        while True:
            tells = self._read_tells()
            have = wanted & tells.keys()
            self._write_status(waiting=len(wanted) - len(have))
            if wanted <= tells.keys():
                return {i: tells[i] for i in ids}
            if time.monotonic() > deadline:
                raise TimeoutError(
                    f"timed out waiting for episodes; have {len(have)}/{len(wanted)}"
                )
            time.sleep(poll)

    # -- persistence -------------------------------------------------------

    def save_checkpoint(self) -> None:
        state = {
            "es": self.es.state_dict(),
            "best": {"fitness": self.best.fitness, "values": self.best.values, "u": self.best.u},
            "pending_ids": self._pending_ids,
            "pending_u": self._pending_u.tolist() if self._pending_u is not None else None,
            "params": list(genome.NAMES),
        }
        _atomic_write_json(self.state_path, state)

    def load_checkpoint(self) -> None:
        state = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.es.load_state(state["es"])
        b = state.get("best", {})
        self.best = Best(b.get("fitness", -float("inf")), b.get("values", {}), b.get("u", []))
        self._pending_ids = state.get("pending_ids", [])
        pu = state.get("pending_u")
        self._pending_u = np.asarray(pu, dtype=float) if pu is not None else None

    def _write_best(self) -> None:
        """Export the best genome as a flat {name: value} map the Fabric client
        loads to play with tuned params (``best.json`` in the run dir)."""
        if not self.best.values:
            return
        _atomic_write_json(self.dir / "best.json", self.best.values)

    def _write_status(self, *, waiting: int, info: dict | None = None) -> None:
        status = {
            "generation": self.es.generation,
            "evals": self.es.counteval,
            "sigma": self.es.sigma,
            "waiting": waiting,
            "best_fitness": self.best.fitness,
            "best_values": self.best.values,
            "updated": time.time(),
        }
        if info:
            status["last_gen"] = info
        _atomic_write_json(self.status_path, status)


def _atomic_write_json(path: Path, obj) -> None:
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(obj, indent=2), encoding="utf-8")
    tmp.replace(path)
