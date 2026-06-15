"""Evolutionary auto-tuner for the autonomous mining agent.

CMA-ES optimizes the agent's hand-picked constants (path costs, movement feel,
FSM thresholds) by running real Minecraft episodes as fitness evaluations. The
optimizer (``Trainer``) and the game communicate through a shared run directory,
so they stay decoupled and a multi-day run survives a restart of either side.

Entry point: ``python -m minesight.evolve`` (use ``--simulate`` to watch it
converge against a synthetic fitness without the game).
"""
from .cmaes import CMAES
from .genome import PARAMS, NAMES, DIM, decode, default_normalized
from .trainer import Trainer, Best, default_run_dir

__all__ = [
    "CMAES",
    "Trainer",
    "Best",
    "PARAMS",
    "NAMES",
    "DIM",
    "decode",
    "default_normalized",
    "default_run_dir",
]
