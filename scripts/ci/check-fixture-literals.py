#!/usr/bin/env python3
"""Find fixture values from locked tests that also appear in production code (design/process.md C-10).

Usage: check-fixture-literals.py

Run from the repository root. Collects string literals from locked test files that look like
an input a test made up (an email address, a UUID, or a key-like word of 8+ characters with both
letters and digits and no space) and reports production files that have the same string as a whole
literal. Screen copy and messages are not collected: the tests and the code share them on purpose,
and they hid the few hits worth reading. Substrings are not counted. A hit can mean the
implementation special-cases a test input. It can also be a legitimate shared value, so this only
reports; a person reads the list in C-10. Always exits 0.
"""

import re
import sys
from pathlib import Path

from lockrules import GENERATED_GLOBS, LOCK_FILE, is_test, matches, parse_lock, walk

LITERAL_RE = re.compile(r"\"((?:[^\"\\\n]|\\.){6,200})\"|'((?:[^'\\\n]|\\.){6,200})'")
PROD_SUFFIXES = {".java", ".kt", ".ts", ".tsx", ".js", ".sql", ".yml", ".yaml", ".properties"}
# Not production: stories show the same copy as the tests, and these files list package names.
NOT_PROD = ("**/*.stories.tsx", "web/pnpm-lock.yaml", "web/pnpm-workspace.yaml", "**/eslint.config.*")


EMAIL_RE = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")
UUID_RE = re.compile(r"(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
KEY_RE = re.compile(r"[A-Za-z0-9_+/=-]{8,}")


def looks_like_data(s):
    if EMAIL_RE.fullmatch(s) or UUID_RE.fullmatch(s):
        return True
    if not KEY_RE.fullmatch(s) or not (re.search(r"[A-Za-z]", s) and re.search(r"\d", s)):
        return False
    # Codes (FILE_TOO_LARGE_2), identifiers (sha256Hex), and names with a version (utf-8, base64url).
    return not re.fullmatch(r"[A-Z0-9_]+|[a-z][a-zA-Z0-9]*|[A-Za-z]+[-_]?\d{1,4}[a-z]*", s)


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
                or matches(path, NOT_PROD)
                or not (path.startswith("server/src/main/") or path.startswith("web/"))):
            continue
        text = (root / path).read_text(encoding="utf-8", errors="replace")
        found = {m.group(1) or m.group(2) for m in LITERAL_RE.finditer(text)}
        for s in sorted(found & literals.keys()):
            hits += 1
            print(f"CHECK  {path} contains {s!r} (from {literals[s]})")
    print(f"{len(literals)} test data literal(s), {hits} hit(s) in production code")
    return 0


if __name__ == "__main__":
    sys.exit(main())
