#!/usr/bin/env python3
"""Find fixture values from locked tests that also appear in production code (design/process.md C-10).

Usage: check-fixture-literals.py

Run from the repository root. Collects string literals from locked test files that look like
test data (an email, text with a space, non-ASCII text, or letters mixed with digits, 6+ chars)
and reports production files that contain the same string. A hit can mean the implementation
special-cases a test input. It can also be a legitimate shared value, so this only reports;
a person reads the list in C-10. Always exits 0.
"""

import re
import sys
from pathlib import Path

from lockrules import GENERATED_GLOBS, LOCK_FILE, is_test, matches, parse_lock, walk

LITERAL_RE = re.compile(r"\"((?:[^\"\\\n]|\\.){6,200})\"|'((?:[^'\\\n]|\\.){6,200})'")
PROD_SUFFIXES = {".java", ".kt", ".ts", ".tsx", ".js", ".sql", ".yml", ".yaml", ".properties"}


def looks_like_data(s):
    if s.startswith(("/", "http", "#", "--", "classpath:")):
        return False
    if re.fullmatch(r"[A-Z0-9_.]+|[A-Za-z-]+|[a-z][a-zA-Z0-9]*", s):
        return False  # codes, header names, identifiers
    return ("@" in s or " " in s or re.search(r"[^\x00-\x7f]", s) is not None
            or (re.search(r"[A-Za-z]", s) and re.search(r"\d", s)))


def main():
    root = Path.cwd()
    lock = root / LOCK_FILE
    if not lock.exists():
        print(f"no {LOCK_FILE}; nothing to check")
        return 0
    literals = {}
    for path in parse_lock(lock.read_text(encoding="utf-8")):
        f = root / path
        if not f.is_file():
            continue
        for m in LITERAL_RE.finditer(f.read_text(encoding="utf-8", errors="replace")):
            s = m.group(1) or m.group(2)
            if looks_like_data(s):
                literals.setdefault(s, path)

    hits = 0
    for path in walk(root):
        if (Path(path).suffix not in PROD_SUFFIXES or is_test(path) or matches(path, GENERATED_GLOBS)
                or not (path.startswith("server/src/main/") or path.startswith("web/"))):
            continue
        text = (root / path).read_text(encoding="utf-8", errors="replace")
        for s, src in literals.items():
            if s in text:
                hits += 1
                print(f"CHECK  {path} contains {s!r} (from {src})")
    print(f"{len(literals)} test data literal(s), {hits} hit(s) in production code")
    return 0


if __name__ == "__main__":
    sys.exit(main())
