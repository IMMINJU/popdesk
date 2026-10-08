#!/usr/bin/env python3
"""Build the table of acceptance criteria to tests, stories, E2E, and gates (design/process.md C-7).

Usage: trace.py SLICE_DIR [--search DIR ...] [--out FILE]

Reads the IDs from the first column of SLICE_DIR/acceptance.md tables (for example S0-AC-13),
then looks for those IDs in test files, stories, E2E, and scripts/ci. --search adds a directory
outside the repository, such as the hidden E2E store.

Fails when a criterion has no test, E2E, or gate, or when a file names an ID that the
slice does not define. A story alone does not count as a test.
"""

import argparse
import re
import sys
from pathlib import Path

from lockrules import is_test, walk

ID_ROW_RE = re.compile(r"^\|\s*([A-Z][A-Z0-9]*-AC-\d+)\s*\|", re.MULTILINE)
TEXT_SUFFIXES = {".java", ".kt", ".ts", ".tsx", ".js", ".mjs", ".py", ".sql", ".sh", ".yaml", ".yml", ".json"}


def kind_of(path):
    if "/e2e/" in f"/{path}":
        return "e2e"
    if path.endswith(".stories.tsx") or path.endswith(".stories.ts"):
        return "story"
    if is_test(path):
        return "test"
    if path.startswith("scripts/ci/") and not path.endswith("trace.py"):
        return "gate"
    return None


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("slice_dir")
    ap.add_argument("--search", action="append", default=[])
    ap.add_argument("--out")
    args = ap.parse_args()

    acceptance = Path(args.slice_dir) / "acceptance.md"
    ids = ID_ROW_RE.findall(acceptance.read_text(encoding="utf-8"))
    if not ids:
        print(f"no criterion IDs found in {acceptance}")
        return 1
    prefix = ids[0].split("-AC-")[0]
    ref_re = re.compile(rf"\b{re.escape(prefix)}-AC-\d+\b")
    defined = set(ids)

    sources = [(Path.cwd(), p, kind_of(p)) for p in walk(Path.cwd())]
    for extra in args.search:
        sources += [(Path(extra), p, "e2e") for p in walk(Path(extra))]

    refs = {i: {"test": set(), "story": set(), "e2e": set(), "gate": set()} for i in ids}
    unknown = []
    for root, path, kind in sources:
        if kind is None or Path(path).suffix not in TEXT_SUFFIXES:
            continue
        try:
            text = (root / path).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for found in set(ref_re.findall(text)):
            if found in defined:
                refs[found][kind].add(Path(path).name)
            else:
                unknown.append(f"{path}: {found}")

    missing = [i for i in ids if not (refs[i]["test"] or refs[i]["e2e"] or refs[i]["gate"])]
    lines = ["| 기준 | 테스트 | story | E2E | 관문 |", "|---|---|---|---|---|"]
    for i in ids:
        cells = [", ".join(sorted(refs[i][k])) or "-" for k in ("test", "story", "e2e", "gate")]
        lines.append(f"| {i} | " + " | ".join(cells) + " |")
    lines.append("")
    lines.append(f"기준 {len(ids)}개, 테스트 없음 {len(missing)}개, 모르는 ID {len(unknown)}개")
    if missing:
        lines.append("테스트 없음: " + ", ".join(missing))
    for u in unknown:
        lines.append(f"모르는 ID: {u}")
    text = "\n".join(lines) + "\n"

    print(text)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    return 1 if (missing or unknown) else 0


if __name__ == "__main__":
    sys.exit(main())
