#!/usr/bin/env python3
"""The upgrade test harness (S0-AC-95, S0-AC-96, S0-AC-104, deploy/upgrade/README.md).

    python deploy/upgrade/upgrade-test.py --sample DIR --image IMAGE --work WORK --project NAME
        [--compose-file FILE ...]

Restores DIR/sample.dump into a new, empty database with `popdesk restore`, starts IMAGE on it, waits
until it is healthy (migrations done), and runs the sample check against DIR/sample.expected.json.
A sample of an older version expects less; what it does not name is not checked.
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
    read_comments, read_issues, read_notifications, read_outbox, read_password_resets, read_photos, sha256)

# The checks every sample has, and those only a later sample has: profile photos and password reset
# links (after 0.1.0), comments and inbox items about issues (after 0.3.0). A later check runs when
# sample.expected.json names it.
CHECKS = ("issues", "attachments", "outbox", "audit")
LATER_CHECKS = ("photos", "passwordResets", "comments", "notifications")

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
    """Every audit row of the sample is still there, unchanged. New rows (the restore, sign-ins) may follow.

    A row is compared by the columns it had in the sample: a later version may add columns (1b added
    seq, prev_hash, and hash to the rows of a 0.1.0 sample), and those are not a change of the row.
    """
    actual = {row["id"]: row for row in read_audit(project)}
    ok = True
    for row in expected["audit"]:
        found = actual.get(row["id"])
        if found is not None and found["action"] == row["action"]:
            now = json.loads(found["row"])
            if all(column in now and now[column] == value for column, value in json.loads(row["row"]).items()):
                continue
        log(f"audit: expected {row}, found {found}")
        ok = False
    return ok


def check_photos(console, expected):
    """Every profile photo of the sample is served with the bytes it had."""
    wanted = expected["photos"]
    try:
        actual = read_photos(console, [photo["accountId"] for photo in wanted])
    except ApiError as e:
        log(f"photos: {e}")
        return False
    if actual != wanted:
        log(f"photos: expected {wanted}, found {actual}")
        return False
    return True


def check_password_resets(project, expected):
    """Every reset link of the sample is still there, unchanged. The app deletes a link once it has
    expired, so one past its time may be gone."""
    actual = {row["accountId"]: row for row in read_password_resets(project)}
    now = datetime.now(timezone.utc)
    ok = True
    for row in expected["passwordResets"]:
        found = actual.get(row["accountId"])
        if found == row:
            continue
        if found is None and datetime.fromisoformat(row["expiresAt"]) < now:
            continue
        log(f"passwordResets: expected {row}, found {found}")
        ok = False
    return ok


def check_rows(name, actual, expected):
    """Every row of the sample is still there, unchanged, by its ID. New rows may follow."""
    found = {row["id"]: row for row in actual}
    ok = True
    for row in expected[name]:
        if found.get(row["id"]) != row:
            log(f"{name}: expected {row}, found {found.get(row['id'])}")
            ok = False
    return ok


def check_comments(project, expected):
    """Every comment of the sample is as it was: edited or not, deleted or not, mentioning the same accounts."""
    return check_rows("comments", read_comments(project), expected)


def check_notifications(project, expected):
    """Every inbox item about an issue is still there for the same account, read or unread as it was."""
    return check_rows("notifications", read_notifications(project), expected)


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
                                ("outbox", check_outbox, project), ("audit", check_audit, project),
                                ("photos", check_photos, console),
                                ("passwordResets", check_password_resets, project),
                                ("comments", check_comments, project),
                                ("notifications", check_notifications, project)):
        if name in LATER_CHECKS and name not in expected:
            continue
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
    result = {"started": False, "checks": {name: False for name in CHECKS}}

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
