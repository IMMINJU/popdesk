#!/usr/bin/env python3
"""Red check: before implementation, every new test must fail on an assertion (design/process.md C-6).

Usage: check-red.py [--allow FILE] [--out FILE] REPORT [REPORT ...]

Run only this slice's new tests first; tests from earlier slices already pass.
REPORT is a JUnit XML file or a directory searched for *.xml (Maven Surefire/Failsafe
`target/*-reports`, Vitest `--reporter=junit`, Playwright `reporter: junit`).

Passes only when there is at least one test and every test ends in <failure>.
  passed   the test checks nothing yet; fix it
  error    it broke for another reason (exception, connection refused, container did not start)
  skipped  skipping is not allowed
No report at all usually means the tests did not compile.

--allow FILE lists tests that may pass before implementation, because the floor, a
framework default, or the C-4 contract stubs already do what they check. One per line: `<test name> | <reason>`,
with the name as this script prints it. Lines starting with # are ignored. A line without
a reason, or a name that is not in the results, fails the check. Allowed tests that pass
are listed in the output with their reason, for the review in C-7.
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
    ap.add_argument("--allow")
    ap.add_argument("--out")
    ap.add_argument("reports", nargs="+")
    args = ap.parse_args()

    allow, allow_errors = {}, []
    if args.allow:
        for n, line in enumerate(Path(args.allow).read_text(encoding="utf-8").splitlines(), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            name, _, reason = (part.strip() for part in line.partition("|"))
            if not reason:
                allow_errors.append(f"{args.allow}:{n}: 이유가 없다: `{name}`")
            allow[name] = reason

    files = list(report_files(args.reports))
    results = [c for f in files for c in cases(f)]
    by_kind = {"failure": [], "passed": [], "error": [], "skipped": [], "allowed": []}
    for name, kind, msg in results:
        if kind == "passed" and name in allow:
            by_kind["allowed"].append((name, allow[name]))
        else:
            by_kind[kind].append((name, msg))
    seen = {name for name, _, _ in results}
    allow_errors += [f"목록에 있지만 결과에 없다: `{name}`" for name in allow if name not in seen]

    ok = bool(results) and not (by_kind["passed"] or by_kind["error"] or by_kind["skipped"] or allow_errors)
    lines = ["# 실패 확인", ""]
    if not files or not results:
        lines.append("테스트 결과가 없다. 컴파일이 안 됐거나 결과 파일 경로가 틀렸다.")
    lines.append(
        f"- 단언 실패 {len(by_kind['failure'])}, 통과 {len(by_kind['passed'])}, "
        f"허용한 통과 {len(by_kind['allowed'])}, "
        f"오류 {len(by_kind['error'])}, 건너뜀 {len(by_kind['skipped'])}"
    )
    lines.append(f"- 판정: {'통과' if ok else '실패'}")
    titles = {
        "passed": "처음부터 통과한 테스트 (아무것도 검사하지 않는다, 고칠 것)",
        "error": "단언이 아닌 이유로 실패한 테스트 (고칠 것)",
        "skipped": "건너뛴 테스트 (허용하지 않는다)",
        "allowed": "처음부터 통과해도 된다고 허용한 테스트 (점검에서 이유를 본다)",
        "failure": "단언에서 실패한 테스트",
    }
    if allow_errors:
        lines += ["", "## 허용 목록이 틀렸다", ""] + [f"- {e}" for e in allow_errors]
    for kind in ("passed", "error", "skipped", "allowed", "failure"):
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
