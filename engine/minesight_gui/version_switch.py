"""Switch the Control Panel (and the code it runs) between MineSight release
lines using git worktrees.

Each release lives in its own directory (a git worktree), so switching never
clobbers your working tree and never forces a rebuild of the version you were
just on. Picking a release ensures its worktree exists and relaunches the GUI
from it. Releases are declared in releases.json at the repo root.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

_HERE = Path(__file__).resolve()


def _git(args: list[str], cwd: Path) -> str:
    return subprocess.run(
        ["git", *args], cwd=str(cwd), check=True,
        capture_output=True, text=True,
    ).stdout.strip()


def repo_root() -> Path:
    """The worktree this GUI is running from."""
    return Path(_git(["rev-parse", "--show-toplevel"], _HERE.parent))


def main_repo() -> Path:
    """The primary worktree (parent of the shared .git common dir)."""
    root = repo_root()
    common = Path(_git(["rev-parse", "--git-common-dir"], root))
    if not common.is_absolute():
        common = (root / common).resolve()
    return common.parent


def load_releases() -> list[dict]:
    f = repo_root() / "releases.json"
    if not f.is_file():
        return []
    return json.loads(f.read_text(encoding="utf-8")).get("releases", [])


def current_ref() -> str:
    return _git(["rev-parse", "--abbrev-ref", "HEAD"], _HERE.parent)


def _worktrees_dir() -> Path:
    m = main_repo()
    return m.parent / f"{m.name}-versions"


def _sanitize(ref: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "_", ref)


def _worktrees_by_branch() -> dict[str, Path]:
    """Map branch short name -> existing worktree path (git worktree list)."""
    out = _git(["worktree", "list", "--porcelain"], _HERE.parent)
    result: dict[str, Path] = {}
    path: Path | None = None
    for line in out.splitlines():
        if line.startswith("worktree "):
            path = Path(line[len("worktree "):])
        elif line.startswith("branch ") and path is not None:
            ref = line[len("branch "):]
            if ref.startswith("refs/heads/"):
                ref = ref[len("refs/heads/"):]
            result[ref] = path
    return result


def worktree_for(ref: str) -> Path:
    """Path to a worktree checked out at ref, reusing or creating one."""
    existing = _worktrees_by_branch().get(ref)
    if existing is not None:
        return existing
    target = _worktrees_dir() / _sanitize(ref)
    if not target.exists():
        _git(["worktree", "add", str(target), ref], main_repo())
    return target


def switch(ref: str) -> Path:
    """Ensure a worktree for ref and relaunch the GUI from it; returns its path.

    The caller should quit the current Control Panel immediately after so its
    sockets (engine 8765, collector 8766) free up. A detached helper waits a
    moment for that, then launches the target version's GUI.
    """
    path = worktree_for(ref)
    cwd = path / "engine"
    helper = (
        "import time, subprocess, sys; time.sleep(2); "
        f"subprocess.Popen([sys.executable, '-m', 'minesight_gui'], cwd=r{str(cwd)!r})"
    )
    kwargs: dict = {}
    if os.name == "nt":
        kwargs["creationflags"] = (
            subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP
        )
    else:
        kwargs["start_new_session"] = True
    subprocess.Popen([sys.executable, "-c", helper], **kwargs)
    return path
