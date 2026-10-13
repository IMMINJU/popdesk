"""Paths that the implementing side must not change (design/process.md section C, guard table).

Shared by tests-lock.py, check-locked-paths.py, trace.py, and the implementing tab's hook.
Paths are repository-relative and use forward slashes.
"""

import hashlib
import re
from pathlib import Path

LOCK_FILE = "scripts/ci/tests.lock"
MIGRATION_DIR = "server/src/main/resources/db/migration/"

# Test code, test support code, and snapshots. The test-writing side owns these.
TEST_GLOBS = [
    "server/src/test/**",
    "web/**/*.test.ts",
    "web/**/*.test.tsx",
    "web/**/__tests__/**",
    "web/**/test-setup.ts",
    "web/**/e2e/**",
    "web/packages/api/src/testing/**",
    "**/__snapshots__/**",
    "**/__screenshots__/**",
]

# Generated from code in step C-4 (D-21, D-43). Implementation does not change the contract.
GENERATED_GLOBS = [
    "docs/integrate/openapi-portal.json",
    "docs/integrate/openapi-console.json",
    "web/packages/api/src/generated/**",
]

# Test runner, mutation, and lint settings. Changing them can drop tests or lower a bar
# without touching a test file. The build files (pom.xml, package.json) also hold such
# settings but change for dependencies too, so check-locked-paths.py only flags those.
TEST_CONFIG_GLOBS = [
    "web/**/vitest.config.*",
    "web/**/vitest.workspace.*",
    "web/**/playwright.config.*",
    "**/stryker.conf*",
    "**/stryker.config.*",
    "**/eslint.config.*",
    "**/.jscpd.json",
    "**/.eslintrc*",
    "**/tsconfig*.json",
    "server/src/main/resources/application-test*",
]

# The gates themselves and the agent settings in a worktree.
GATE_GLOBS = [
    "scripts/ci/**",
    ".github/**",
    ".claude/**",
    "AGENTS.md",
    "CLAUDE.md",
    "design/slices/*/pending.txt",
]

# What the test-writing side may touch besides TEST_GLOBS.
TEST_SIDE_EXTRA_GLOBS = [
    LOCK_FILE,
    "design/slices/*/questions.md",
    "design/slices/*/red.md",
    "design/slices/*/red-allow.txt",
]

SKIP_DIRS = {".git", "node_modules", "target", "build", "dist", ".gradle", ".claude", ".stryker-tmp"}


def _glob_to_re(glob):
    out, i = [], 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif glob.startswith("**", i):
            out.append(".*")
            i += 2
        elif glob[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(glob[i]))
            i += 1
    return re.compile("^" + "".join(out) + "$")


_CACHE = {}


def matches(path, globs):
    path = path.replace("\\", "/")
    for g in globs:
        if g not in _CACHE:
            _CACHE[g] = _glob_to_re(g)
        if _CACHE[g].match(path):
            return True
    return False


def is_test(path):
    return matches(path, TEST_GLOBS)


def is_migration(path):
    return path.replace("\\", "/").startswith(MIGRATION_DIR)


def file_hash(data):
    """sha256 of the content with CRLF turned into LF, so Windows and CI agree."""
    return hashlib.sha256(data.replace(b"\r\n", b"\n")).hexdigest()


def walk(root):
    """Yield repository-relative paths of all files under root, skipping build and tool dirs."""
    root = Path(root)
    stack = [root]
    while stack:
        d = stack.pop()
        for p in d.iterdir():
            if p.is_dir():
                if p.name not in SKIP_DIRS:
                    stack.append(p)
            elif p.is_file():
                yield p.relative_to(root).as_posix()


def parse_lock(text):
    """Return {path: hash} from tests.lock text."""
    entries = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        digest, path = line.split(None, 1)
        entries[path] = digest
    return entries
