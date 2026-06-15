"""The genome: which agent knobs we tune, their bounds, and the (de)coding.

This is the contract between the Python optimizer and the Java client. Every
name here must match a field in the Java ``AgentParams`` class; the client reads
a candidate's ``values`` map and applies them to a fresh agent before an episode.

CMA-ES searches in a *normalized* space where every parameter is mapped to
roughly [0, 1] (raw -> (raw-lo)/(hi-lo)). That keeps all dimensions on a
comparable scale, which is exactly what CMA-ES wants at start-up (isotropic
initial covariance). Out-of-box values are clamped back into range on decode,
and a small quadratic boundary penalty nudges the search to stay feasible.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class Param:
    name: str
    lo: float
    hi: float
    default: float
    is_int: bool = False
    note: str = ""


# The tunable surface of the autonomous miner. Defaults mirror the current
# hand-picked constants in PathFinder / AutoWalker / MiningAgent, so a genome at
# its defaults reproduces today's behavior exactly.
PARAMS: tuple[Param, ...] = (
    # -- PathFinder: route shaping ---------------------------------------
    Param("mine_cost", 1.0, 12.0, 5.0, note="cost of mining one block, in walk-steps"),
    Param("place_cost", 1.0, 15.0, 6.0, note="cost of placing a bridge block"),
    Param("lava_near_cost", 0.0, 20.0, 8.0, note="penalty for routing beside lava"),
    Param("max_drop", 1, 5, 3, is_int=True, note="biggest safe drop, blocks"),
    # -- AutoWalker: movement feel ---------------------------------------
    Param("waypoint_radius", 0.3, 1.2, 0.6, note="how close counts as 'reached'"),
    Param("stuck_window", 20, 80, 40, is_int=True, note="ticks before a stuck check"),
    Param("stuck_min_move", 0.1, 1.0, 0.35, note="min blocks moved per window"),
    Param("sprint_dist", 0.8, 3.0, 1.5, note="sprint when farther than this from wp"),
    # -- MiningAgent: FSM behavior ---------------------------------------
    Param("explore_step", 2, 12, 5, is_int=True, note="strip-tunnel segment length"),
    Param("mine_timeout", 60, 400, 200, is_int=True, note="ticks before giving up a block"),
    Param("repath", 5, 60, 20, is_int=True, note="ticks between re-paths while GOTO"),
    Param("reach_dist", 3.0, 6.0, 4.5, note="how close to an ore counts as in-reach"),
    Param("rare_weight", 1.0, 8.0, 2.0, note="distance discount for preferring rare ore"),
)

NAMES: tuple[str, ...] = tuple(p.name for p in PARAMS)
DIM = len(PARAMS)

_LO = np.array([p.lo for p in PARAMS], dtype=float)
_HI = np.array([p.hi for p in PARAMS], dtype=float)
_SPAN = _HI - _LO
_IS_INT = np.array([p.is_int for p in PARAMS], dtype=bool)


def default_normalized() -> np.ndarray:
    """The CMA-ES starting point: today's constants, mapped to [0, 1]."""
    raw = np.array([p.default for p in PARAMS], dtype=float)
    return (raw - _LO) / _SPAN


def decode(u: np.ndarray) -> dict[str, float]:
    """Normalized vector -> a {name: value} map of in-range agent params.

    Values outside [0, 1] are clamped (rounded for integer params).
    """
    raw = _LO + np.clip(np.asarray(u, dtype=float), 0.0, 1.0) * _SPAN
    out: dict[str, float] = {}
    for i, p in enumerate(PARAMS):
        v = float(raw[i])
        out[p.name] = float(round(v)) if p.is_int else v
    return out


def boundary_penalty(u: np.ndarray) -> float:
    """Quadratic cost for straying outside the feasible box (0 when in-range)."""
    u = np.asarray(u, dtype=float)
    over = u - np.clip(u, 0.0, 1.0)
    return float(np.sum(over ** 2))
