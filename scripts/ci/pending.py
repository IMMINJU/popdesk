#!/usr/bin/env python3
"""Print the runner arguments that leave out tests still waiting for implementation.

Usage: pending.py server|web

Reads design/slices/*/pending.txt (lines `server <Surefire pattern>` or `web <Vitest glob>`).
While a slice is between its tests (C-5) and its implementation (C-9), its tests are on main
and fail on purpose. CI leaves them out so that every other test must still pass; the main
session removes a line when the implementing tab that makes those tests pass is merged.

server: writes the patterns to target/pending-excludes.txt (run from server/) and prints
  -Dsurefire.excludesFile=<that file>
web: prints --exclude '<glob>' for each pattern (passed through `pnpm -r run test`).
Prints nothing when no pattern is pending.
"""

import shlex
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("server", "web"):
        print(__doc__, file=sys.stderr)
        return 2
    kind = sys.argv[1]
    root = Path(__file__).resolve().parents[2]
    patterns = []
    for f in sorted(root.glob("design/slices/*/pending.txt")):
        for line in f.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            side, _, pattern = line.partition(" ")
            if side not in ("server", "web") or not pattern.strip():
                print(f"{f}: bad line: {line}", file=sys.stderr)
                return 1
            if side == kind:
                patterns.append(pattern.strip())
    if not patterns:
        return 0
    if kind == "server":
        out = root / "server" / "target" / "pending-excludes.txt"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text("\n".join(patterns) + "\n", encoding="utf-8")
        print(f"-Dsurefire.excludesFile={out.as_posix()}")
    else:
        print(" ".join(f"--exclude {shlex.quote(p)}" for p in patterns))
    return 0


if __name__ == "__main__":
    sys.exit(main())
