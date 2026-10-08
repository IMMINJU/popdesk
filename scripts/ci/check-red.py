#!/usr/bin/env python3
"""Red check: before implementation, every new test must fail on an assertion (design/process.md C-6).

Usage: check-red.py [--out FILE] REPORT [REPORT ...]

Run only this slice's new tests first; tests from earlier slices already pass.
REPORT is a JUnit XML file or a directory searched for *.xml (Maven Surefire/Failsafe
`target/*-reports`, Vitest `--reporter=junit`, Playwright `reporter: junit`).

Passes only when there is at least one test and every test ends in <failure>.
  passed   the test checks nothing yet; fix it
  error    it broke for another reason (exception, connection refused, container did not start)
  skipped  skipping is not allowed
No report at all usually means the tests did not compile.
--out writes the result as Markdown, for design/slices/<slice>/red.md.
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def report_files(args):
    for a in args:
        p = Path(a)
        if p.is_dir():
            yield from sorted(p.rglob("*.xml"))
        elif p.is_file():
            yield p


def cases(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return
    for tc in root.iter("testcase"):
        name = f"{tc.get('classname', '')}.{tc.get('name', '')}".strip(".")
        kinds = {child.tag for child in tc}
        if "error" in kinds:
            kind, el = "error", tc.find("error")
        elif "failure" in kinds:
            kind, el = "failure", tc.find("failure")
        elif "skipped" in kinds:
            kind, el = "skipped", None
        else:
            kind, el = "passed", None
        msg = ""
        if el is not None:
            msg = (el.get("message") or el.get("type") or "").strip().splitlines()[0:1]
            msg = msg[0][:160] if msg else ""
        yield name, kind, msg


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out")
    ap.add_argument("reports", nargs="+")
    args = ap.parse_args()

    files = list(report_files(args.reports))
    results = [c for f in files for c in cases(f)]
    by_kind = {"failure": [], "passed": [], "error": [], "skipped": []}
    for name, kind, msg in results:
        by_kind[kind].append((name, msg))

    ok = bool(results) and not (by_kind["passed"] or by_kind["error"] or by_kind["skipped"])
    lines = ["# 실패 확인", ""]
    if not files or not results:
        lines.append("테스트 결과가 없다. 컴파일이 안 됐거나 결과 파일 경로가 틀렸다.")
    lines.append(
        f"- 단언 실패 {len(by_kind['failure'])}, 통과 {len(by_kind['passed'])}, "
        f"오류 {len(by_kind['error'])}, 건너뜀 {len(by_kind['skipped'])}"
    )
    lines.append(f"- 판정: {'통과' if ok else '실패'}")
    titles = {
        "passed": "처음부터 통과한 테스트 (아무것도 검사하지 않는다, 고칠 것)",
        "error": "단언이 아닌 이유로 실패한 테스트 (고칠 것)",
        "skipped": "건너뛴 테스트 (허용하지 않는다)",
        "failure": "단언에서 실패한 테스트",
    }
    for kind in ("passed", "error", "skipped", "failure"):
        if by_kind[kind]:
            lines += ["", f"## {titles[kind]}", ""]
            lines += [f"- `{n}`" + (f": {m}" if m else "") for n, m in by_kind[kind]]
    text = "\n".join(lines) + "\n"

    print(text)
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8", newline="\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
