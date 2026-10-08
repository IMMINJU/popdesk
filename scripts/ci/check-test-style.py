#!/usr/bin/env python3
"""Check how tests are written (design/process.md C-5, C-7).

Usage: check-test-style.py [--all-strict]

Run from the repository root.

Any test file fails on:
  @Disabled, @Ignore, .skip(, .only(, .todo(, .fixme(, xit(, xdescribe(
  assertions that are always true: assertTrue(true), assertThat(true).isTrue(), expect(true).toBe(true)

Locked test files (in scripts/ci/tests.lock) also fail on:
  mocks: @MockBean, @MockitoBean, @SpyBean, @MockitoSpyBean, @Mock, @Spy, Mockito., mock(, vi.mock(
    Locked tests call the app over HTTP and the generated client. Outside systems run as real
    stand-ins (Testcontainers Postgres, GreenMail, MinIO, a test webhook receiver, a fixed Clock),
    so a locked test has nothing to mock.
  fixed waits: Thread.sleep(, waitForTimeout(, setTimeout(  (use Awaitility or expect.poll)

Other test files (unit tests the implementing side added) get the same two as warnings.
--all-strict treats every test file as locked; the test-writing side uses it before the lock exists.

A line that ends with a comment holding `style-allowed: <reason>` is reported but does not fail.
"""

import re
import sys
from pathlib import Path

from lockrules import LOCK_FILE, is_test, parse_lock, walk

ALWAYS = [
    (re.compile(r"@(Disabled|Ignore)\b"), "disabled test"),
    (re.compile(r"\b(it|test|describe|suite)\.(skip|only|todo|fixme)\s*\("), "skipped or focused test"),
    (re.compile(r"\bx(it|describe|test)\s*\("), "skipped test"),
    (re.compile(r"\bassertTrue\s*\(\s*true\s*\)"), "always-true assertion"),
    (re.compile(r"assertThat\s*\(\s*true\s*\)\s*\.isTrue\s*\(\s*\)"), "always-true assertion"),
    (re.compile(r"\bexpect\s*\(\s*true\s*\)\s*\.\s*toBe(Truthy)?\s*\(\s*(true)?\s*\)"), "always-true assertion"),
]
LOCKED_ONLY = [
    (re.compile(r"@(MockBean|MockitoBean|SpyBean|MockitoSpyBean|Mock|Spy)\b"), "mock"),
    (re.compile(r"\bMockito\s*\.|(?<![\w.])mock\s*\(|\bvi\.mock\s*\("), "mock"),
    (re.compile(r"\bThread\.sleep\s*\(|\bwaitForTimeout\s*\(|\bsetTimeout\s*\("), "fixed wait"),
]
ALLOW_RE = re.compile(r"(//|#)\s*style-allowed:\s*\S")
SUFFIXES = {".java", ".kt", ".ts", ".tsx", ".js", ".mjs"}


def main():
    all_strict = "--all-strict" in sys.argv[1:]
    root = Path.cwd()
    lock = root / LOCK_FILE
    locked = set(parse_lock(lock.read_text(encoding="utf-8"))) if lock.exists() else set()

    errors, warnings = [], []
    files = [p for p in walk(root) if is_test(p) and Path(p).suffix in SUFFIXES]
    for path in files:
        strict = all_strict or path in locked
        for n, line in enumerate((root / path).read_text(encoding="utf-8").splitlines(), 1):
            allowed = bool(ALLOW_RE.search(line))
            for rules, severe in ((ALWAYS, True), (LOCKED_ONLY, strict)):
                for rx, what in rules:
                    if rx.search(line):
                        msg = f"{path}:{n}: {what}: {line.strip()[:120]}"
                        (warnings if (allowed or not severe) else errors).append(msg)

    for w in warnings:
        print(f"WARN   {w}")
    for e in errors:
        print(f"ERROR  {e}")
    print(f"{len(files)} test file(s), {len(locked)} locked: {'FAIL' if errors else 'ok'}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
