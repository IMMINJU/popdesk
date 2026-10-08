#!/usr/bin/env python3
"""Write or check scripts/ci/tests.lock, the sha256 of every test file (design/process.md C-6).

Usage:
  tests-lock.py write   Test-writing side, after the red check passes. Records every test file.
  tests-lock.py check   Fails if a recorded file is missing or its content changed.

Run from the repository root. `check` passes when there is no lock file yet.
Test files that are not in the lock (unit tests the implementing side added) are allowed.
"""

import sys
from pathlib import Path

from lockrules import LOCK_FILE, file_hash, is_test, parse_lock, walk

HEADER = (
    "# sha256 of each locked test file (CRLF read as LF). Written by scripts/ci/tests-lock.py write.\n"
    "# The implementing side must not change these files or this list (design/process.md C).\n"
)


def write(root):
    paths = sorted(p for p in walk(root) if is_test(p))
    lines = [f"{file_hash((root / p).read_bytes())}  {p}\n" for p in paths]
    (root / LOCK_FILE).write_text(HEADER + "".join(lines), encoding="utf-8", newline="\n")
    print(f"locked {len(paths)} test files in {LOCK_FILE}")
    return 0


def check(root):
    lock = root / LOCK_FILE
    if not lock.exists():
        print(f"no {LOCK_FILE}; nothing to check")
        return 0
    entries = parse_lock(lock.read_text(encoding="utf-8"))
    missing, changed = [], []
    for path, digest in entries.items():
        f = root / path
        if not f.is_file():
            missing.append(path)
        elif file_hash(f.read_bytes()) != digest:
            changed.append(path)
    for p in missing:
        print(f"MISSING  {p}")
    for p in changed:
        print(f"CHANGED  {p}")
    if missing or changed:
        print(f"{len(missing) + len(changed)} locked test file(s) were deleted or edited. "
              "If a test is wrong, report it; the test-writing side fixes it and rewrites the lock.")
        return 1
    print(f"{len(entries)} locked test files unchanged")
    return 0


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("write", "check"):
        print(__doc__)
        return 2
    root = Path.cwd()
    return write(root) if sys.argv[1] == "write" else check(root)


if __name__ == "__main__":
    sys.exit(main())
