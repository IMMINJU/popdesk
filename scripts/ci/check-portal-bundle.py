#!/usr/bin/env python3
"""The customer portal's build output holds no agent console API path (ADR D-43).

Usage: check-portal-bundle.py [DIST_DIR]

Run from the repository root after `pnpm build`. Reads every file under web/portal/dist as
bytes and fails when one contains "/api/console". A shared web package that pulls the console
client into the portal shows up here.
"""

import sys
from pathlib import Path

NEEDLE = b"/api/console"


def main():
    dist = Path(sys.argv[1] if len(sys.argv) > 1 else "web/portal/dist")
    if not dist.is_dir():
        print(f"{dist} is missing. Build the portal first.")
        return 2
    files = [p for p in dist.rglob("*") if p.is_file()]
    if not files:
        print(f"{dist} is empty. Build the portal first.")
        return 2
    hits = [(p, p.read_bytes().count(NEEDLE)) for p in files]
    hits = [(p, n) for p, n in hits if n]
    for p, n in hits:
        print(f"{p.as_posix()}: {n} occurrence(s) of {NEEDLE.decode()}")
    if hits:
        print("The portal bundle must not contain console API paths.")
        return 1
    print(f"{len(files)} portal build file(s): no {NEEDLE.decode()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
