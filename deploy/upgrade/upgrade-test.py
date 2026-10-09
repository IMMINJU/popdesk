#!/usr/bin/env python3
"""The upgrade test harness (S0-AC-95, S0-AC-96, S0-AC-104, deploy/upgrade/README.md).

    python deploy/upgrade/upgrade-test.py --sample DIR --image IMAGE --work WORK --project NAME
        [--compose-file FILE ...]

Restores DIR/sample.dump into a new, empty database with `popdesk restore`, starts IMAGE on it, waits
until it is healthy (migrations done), and runs the sample check against DIR/sample.expected.json.
Each --compose-file is added after deploy/compose.yml. Writes WORK/result.json and WORK/app.log.

Exit codes: 0 the app started and every check passed; 1 the app started and a check failed; 2 the app
did not start or did not finish migrating; 3 the harness itself failed.
"""

import argparse
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from popdesk_stack import (  # noqa: E402
    ADMIN_EMAIL, ADMIN_PASSWORD, CONSOLE_HOST, FAILED_ROWS_KEPT_DAYS, ApiError, Project, log, read_audit,
    read_issues, read_outbox, sha256)

# The app's health check gives a start period of 3 minutes and then 6 tries 10 seconds apart; a long
# migration on a large sample can take longer, so the harness waits longer than that.
START_TIMEOUT = 15 * 60


def check_issues(console, expected):
    actual = read_issues(console)
    if actual != expected["issues"]:
        log(f"issues: expected {expected['issues']}, found {actual}")
        return False
    return True


def check_attachments(console, expected):
    ok = True
    for attachment in expected["attachments"]:
        path = f"/api/console/issues/{attachment['issue']}/attachments/{attachment['id']}"
        status, data = console.request("GET", path)
        if status != 200 or sha256(data) != attachment["sha256"]:
            log(f"attachments: {path} answered {status}, sha256 {sha256(data) if status == 200 else '-'}, "
                f"expected {attachment['sha256']}")
            ok = False
    return ok


def check_outbox(project, expected):
    """Every failed row of the sample is still there, unchanged. The app deletes a failed row 7 days after
    it failed, so a row that old may be gone."""
    actual = {row["id"]: row for row in read_outbox(project)}
    cutoff = datetime.now(timezone.utc) - timedelta(days=FAILED_ROWS_KEPT_DAYS)
    ok = True
    for row in expected["outbox"]:
        found = actual.get(row["id"])
        if found == row:
            continue
        if found is None and datetime.fromisoformat(row["failedAt"]) < cutoff:
            continue
        log(f"outbox: expected {row}, found {found}")
        ok = False
    return ok


def check_audit(project, expected):
    """Every audit row of the sample is still there, unchanged. New rows (the restore, sign-ins) may follow."""
    actual = {row["id"]: row for row in read_audit(project)}
    ok = True
    for row in expected["audit"]:
        if actual.get(row["id"]) != row:
            log(f"audit: expected {row}, found {actual.get(row['id'])}")
            ok = False
    return ok


def run_checks(project, expected):
    checks = {}
    console = project.api(CONSOLE_HOST)
    try:
        console.sign_in(ADMIN_EMAIL, ADMIN_PASSWORD)
        signed_in = True
    except ApiError as e:
        log(f"Signing in as the sample's admin failed: {e}")
        signed_in = False
    for name, check, target in (("issues", check_issues, console), ("attachments", check_attachments, console),
                                ("outbox", check_outbox, project), ("audit", check_audit, project)):
        if target is console and not signed_in:
            checks[name] = False
            continue
        try:
            checks[name] = check(target, expected)
        except ApiError as e:
            log(f"{name}: {e}")
            checks[name] = False
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--sample", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--work", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", action="append", default=[])
    args = parser.parse_args()
    sample = Path(args.sample).resolve()
    work = Path(args.work).resolve()
    work.mkdir(parents=True, exist_ok=True)
    result = {"started": False, "checks": {"issues": False, "attachments": False, "outbox": False, "audit": False}}

    project = Project(args.project, args.image, work, extra_files=args.compose_file)
    code = 3
    try:
        expected = json.loads((sample / "sample.expected.json").read_text(encoding="utf-8"))
        dump = (sample / "sample.dump").as_posix()
        log(f"Restoring {dump} for {args.image} as {args.project}")
        project.up("db")
        if not project.wait_healthy("db", 300):
            raise RuntimeError("The database did not become healthy.")
        restore = project.compose("run", "--rm", "-T", "-v", f"{dump}:/tmp/restore.dump:ro", "app", "popdesk",
                                  "restore", "/tmp/restore.dump", check=False, timeout=1800)
        if restore.returncode != 0:
            raise RuntimeError("popdesk restore failed:\n" + restore.stdout.decode(errors="replace")
                               + restore.stderr.decode(errors="replace"))
        project.up("app")
        result["started"] = project.wait_healthy("app", START_TIMEOUT)
        if not result["started"]:
            log("The app did not start; see app.log.")
            code = 2
        else:
            result["checks"] = run_checks(project, expected)
            code = 0 if all(result["checks"].values()) else 1
    except Exception as e:  # noqa: BLE001 - any failure of the harness itself is exit code 3
        log(f"upgrade-test.py: {e}")
        code = 3
    finally:
        try:
            if project.started:
                (work / "app.log").write_text(project.logs("app"), encoding="utf-8")
        finally:
            (work / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            project.down()
            project.override.unlink(missing_ok=True)
    log(f"result: {json.dumps(result)} (exit {code})")
    return code


if __name__ == "__main__":
    sys.exit(main())
