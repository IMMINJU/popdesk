#!/usr/bin/env python3
"""Check what a branch changed against the rules for its side (design/process.md C).

Usage: check-locked-paths.py --base REF [--head REF] [--role impl|tests|any]

  --role impl   Implementing side. Must not touch locked tests, the lock file, generated
                contract files, the gates, or merged migrations.
  --role tests  Test-writing side. May touch only test paths, the lock file, and the
                slice's questions.md and red.md.
  --role any    CI. Merged migrations stay as they are, and one branch must not change both
                locked tests and production code (the side that writes tests does not implement).

Run from the repository root. Compares the merge base of REF and HEAD with HEAD.
Changes to build or dependency files are printed as "needs a human look" but do not fail.
"""

import argparse
import subprocess
import sys

from lockrules import (
    GATE_GLOBS,
    GENERATED_GLOBS,
    LOCK_FILE,
    TEST_CONFIG_GLOBS,
    TEST_SIDE_EXTRA_GLOBS,
    is_migration,
    is_test,
    matches,
    parse_lock,
)

DEPENDENCY_GLOBS = [
    "**/pom.xml",
    "**/package.json",
    "**/pnpm-lock.yaml",
    "**/pnpm-workspace.yaml",
    "**/Dockerfile",
    "compose*.yaml",
    "**/compose*.yaml",
]


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def changes(base, head):
    """Return [(status, path)] where a rename yields both the old and the new path."""
    out = git("diff", "--name-status", "-M", f"{base}...{head}")
    result = []
    for line in out.splitlines():
        parts = line.split("\t")
        status = parts[0][0]
        if status == "R":
            result.append(("D", parts[1]))
            result.append(("A", parts[2]))
        else:
            result.append((status, parts[1]))
    return result


def base_lock(base):
    try:
        return parse_lock(git("show", f"{base}:{LOCK_FILE}"))
    except subprocess.CalledProcessError:
        return {}


def base_has(base, path):
    return subprocess.run(["git", "cat-file", "-e", f"{base}:{path}"], capture_output=True).returncode == 0


def is_production(path):
    return path.startswith("server/src/main/") or (
        path.startswith("web/") and not is_test(path) and not matches(path, GENERATED_GLOBS)
        and not path.endswith(".stories.tsx")
    )


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", required=True)
    ap.add_argument("--head", default="HEAD")
    ap.add_argument("--role", choices=["impl", "tests", "any"], default="any")
    args = ap.parse_args()

    merge_base = git("merge-base", args.base, args.head).strip()
    changed = changes(args.base, args.head)
    locked = base_lock(merge_base)
    errors, notes = [], []

    for status, path in changed:
        if is_migration(path) and status != "A" and base_has(merge_base, path):
            errors.append(f"merged migration changed ({status}): {path}  (D-24, migrations only move forward)")
        if matches(path, DEPENDENCY_GLOBS):
            notes.append(f"needs a human look, build or dependency file changed: {path}")

        if args.role == "impl":
            if path == LOCK_FILE:
                errors.append(f"implementing side changed the lock file: {path}")
            elif path in locked:
                errors.append(f"implementing side changed a locked test ({status}): {path}")
            elif matches(path, GENERATED_GLOBS):
                errors.append(f"implementing side changed a generated contract file: {path}  (back to C-4)")
            elif matches(path, GATE_GLOBS):
                errors.append(f"implementing side changed a gate or agent setting: {path}")
            elif matches(path, TEST_CONFIG_GLOBS):
                errors.append(f"implementing side changed a test runner or lint setting: {path}")
        elif args.role == "tests":
            if not (is_test(path) or matches(path, TEST_SIDE_EXTRA_GLOBS)):
                errors.append(f"test-writing side changed a file outside the test paths: {path}")

    if args.role == "any":
        touched_locked = [p for _, p in changed if p in locked or p == LOCK_FILE]
        touched_prod = [p for _, p in changed if is_production(p)]
        if touched_locked and touched_prod:
            errors.append(
                "one branch changed both locked tests and production code; the side that writes tests "
                f"does not implement. Locked: {touched_locked[:3]} Production: {touched_prod[:3]}"
            )

    for n in notes:
        print(f"NOTE   {n}")
    for e in errors:
        print(f"ERROR  {e}")
    print(f"{len(changed)} changed path(s), role {args.role}: "
          f"{'FAIL' if errors else 'ok'}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
