"""A compact, dependency-light CMA-ES (only numpy).

Covariance Matrix Adaptation Evolution Strategy - the standard
(mu/mu_w, lambda) variant from Hansen's tutorial. We use it instead of a
textbook genetic algorithm because it is dramatically more sample-efficient:
each fitness evaluation here is a multi-minute Minecraft episode, so every
wasted candidate is real wall-clock time. CMA-ES adapts a full covariance
(step size + correlations between parameters), so it homes in on a good
parameter vector in far fewer episodes than crossover/mutation would.

Minimization by convention. The trainer negates episode fitness (higher is
better in-game) into a cost (lower is better here).

The optimizer is deliberately decoupled from Minecraft: ``ask()`` hands out
candidate vectors, you evaluate them however you like, and ``tell()`` feeds the
costs back. State serializes to plain JSON-able dicts so a multi-day run
survives a restart of either process.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass
class CMAES:
    """(mu/mu_w, lambda)-CMA-ES over R^n. Minimizes.

    Args:
        x0:     initial mean (the starting parameter vector).
        sigma0: initial step size (in the same units as x0).
        popsize: lambda; defaults to the standard 4 + floor(3 ln n).
        seed:   RNG seed for reproducible runs/tests.
    """

    x0: np.ndarray
    sigma0: float
    popsize: int | None = None
    seed: int | None = None

    def __post_init__(self) -> None:
        x0 = np.asarray(self.x0, dtype=float).ravel()
        n = x0.size
        self.n = n
        self.mean = x0.copy()
        self.sigma = float(self.sigma0)
        self.rng = np.random.default_rng(self.seed)

        # Selection: lambda samples, mu parents, log-decreasing recombination weights.
        lam = self.popsize or (4 + int(np.floor(3 * np.log(n))))
        self.lam = int(lam)
        mu = self.lam // 2
        self.mu = mu
        w = np.log(mu + 0.5) - np.log(np.arange(1, mu + 1))
        w /= w.sum()
        self.weights = w
        self.mueff = 1.0 / np.sum(w ** 2)

        # Adaptation rates (Hansen's recommended settings).
        self.cc = (4 + self.mueff / n) / (n + 4 + 2 * self.mueff / n)
        self.cs = (self.mueff + 2) / (n + self.mueff + 5)
        self.c1 = 2 / ((n + 1.3) ** 2 + self.mueff)
        self.cmu = min(
            1 - self.c1,
            2 * (self.mueff - 2 + 1 / self.mueff) / ((n + 2) ** 2 + self.mueff),
        )
        self.damps = 1 + 2 * max(0.0, np.sqrt((self.mueff - 1) / (n + 1)) - 1) + self.cs
        self.chiN = np.sqrt(n) * (1 - 1 / (4 * n) + 1 / (21 * n ** 2))

        # Dynamic state.
        self.pc = np.zeros(n)
        self.ps = np.zeros(n)
        self.C = np.eye(n)
        self.B = np.eye(n)
        self.D = np.ones(n)
        self.invsqrtC = np.eye(n)
        self.counteval = 0
        self.eigeneval = 0
        self.generation = 0
        self._candidates: np.ndarray | None = None

    # -- core loop ---------------------------------------------------------

    def ask(self) -> np.ndarray:
        """Sample a fresh population. Returns a (lambda, n) array of candidates."""
        z = self.rng.standard_normal((self.lam, self.n))
        y = z @ (self.B * self.D).T            # correlated, scaled steps
        self._candidates = self.mean + self.sigma * y
        return self._candidates.copy()

    def tell(self, candidates: np.ndarray, costs: np.ndarray) -> None:
        """Update the distribution from evaluated candidates (lower cost = better)."""
        candidates = np.asarray(candidates, dtype=float)
        costs = np.asarray(costs, dtype=float)
        n, lam = self.n, self.lam
        self.counteval += lam

        order = np.argsort(costs)
        xsorted = candidates[order]
        xold = self.mean.copy()

        # Recombination: new mean is the weighted average of the mu best.
        self.mean = self.weights @ xsorted[: self.mu]

        # Step-size evolution path.
        y = (self.mean - xold) / self.sigma
        self.ps = (1 - self.cs) * self.ps + np.sqrt(
            self.cs * (2 - self.cs) * self.mueff
        ) * (self.invsqrtC @ y)
        ps_norm = np.linalg.norm(self.ps)
        hsig = (
            ps_norm / np.sqrt(1 - (1 - self.cs) ** (2 * self.counteval / lam)) / self.chiN
            < 1.4 + 2 / (n + 1)
        )

        # Covariance evolution path.
        self.pc = (1 - self.cc) * self.pc + (
            hsig * np.sqrt(self.cc * (2 - self.cc) * self.mueff)
        ) * y

        # Rank-one + rank-mu covariance update.
        artmp = (xsorted[: self.mu] - xold) / self.sigma
        self.C = (
            (1 - self.c1 - self.cmu) * self.C
            + self.c1 * (np.outer(self.pc, self.pc)
                         + (0 if hsig else self.cc * (2 - self.cc)) * self.C)
            + self.cmu * (artmp.T * self.weights) @ artmp
        )

        # Step-size update.
        self.sigma *= np.exp((self.cs / self.damps) * (ps_norm / self.chiN - 1))

        self._update_eigensystem()
        self.generation += 1

    def _update_eigensystem(self) -> None:
        # Lazily refresh B, D, invsqrtC - the costly eig only every so often.
        if self.counteval - self.eigeneval <= self.lam / (self.c1 + self.cmu) / self.n / 10:
            return
        self.eigeneval = self.counteval
        self.C = np.triu(self.C) + np.triu(self.C, 1).T   # enforce symmetry
        vals, self.B = np.linalg.eigh(self.C)
        vals = np.clip(vals, 1e-20, None)                 # guard tiny/negative
        self.D = np.sqrt(vals)
        self.invsqrtC = self.B @ np.diag(1.0 / self.D) @ self.B.T

    # -- introspection -----------------------------------------------------

    @property
    def best_guess(self) -> np.ndarray:
        """Current distribution mean - the optimizer's best parameter estimate."""
        return self.mean.copy()

    def converged(self, tol: float = 1e-11) -> bool:
        """True once the search distribution has collapsed (sigma * max-std tiny)."""
        return bool(self.sigma * np.max(self.D) < tol)

    # -- persistence -------------------------------------------------------

    def state_dict(self) -> dict:
        return {
            "n": self.n,
            "mean": self.mean.tolist(),
            "sigma": self.sigma,
            "pc": self.pc.tolist(),
            "ps": self.ps.tolist(),
            "C": self.C.tolist(),
            "counteval": self.counteval,
            "eigeneval": self.eigeneval,
            "generation": self.generation,
            "lam": self.lam,
        }

    def load_state(self, s: dict) -> None:
        self.mean = np.asarray(s["mean"], dtype=float)
        self.sigma = float(s["sigma"])
        self.pc = np.asarray(s["pc"], dtype=float)
        self.ps = np.asarray(s["ps"], dtype=float)
        self.C = np.asarray(s["C"], dtype=float)
        self.counteval = int(s["counteval"])
        self.eigeneval = int(s["eigeneval"])
        self.generation = int(s["generation"])
        # Rebuild the eigensystem from the restored covariance.
        self.C = np.triu(self.C) + np.triu(self.C, 1).T
        vals, self.B = np.linalg.eigh(self.C)
        vals = np.clip(vals, 1e-20, None)
        self.D = np.sqrt(vals)
        self.invsqrtC = self.B @ np.diag(1.0 / self.D) @ self.B.T
