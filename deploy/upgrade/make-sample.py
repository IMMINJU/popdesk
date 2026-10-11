#!/usr/bin/env python3
"""Makes the sample data dump for upgrade tests (S0-AC-95, deploy/upgrade/README.md).

    python deploy/upgrade/make-sample.py --image IMAGE --out DIR --project NAME

Starts IMAGE on an empty database, fills every business table of this version through the API (SQL
only for states the API cannot reach), runs `popdesk backup`, and writes DIR/sample.dump,
DIR/sample.image (the image ID that made it), and DIR/sample.expected.json (what the sample check
expects). Exit code 0 when all of this worked; 3 when something failed.
"""

import argparse
import json
import random
import struct
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from popdesk_stack import (  # noqa: E402
    ADMIN_EMAIL, ADMIN_PASSWORD, CONSOLE_HOST, PEOPLE_PASSWORD, PORTAL_HOST, HarnessError, Project, image_id,
    log, read_audit, read_issues, read_outbox, read_password_resets, read_photos, sha256)

# Low, so a few small uploads pass 80% and raise the quota alert (ops_alert, notification rows).
QUOTA = "1MB"


def token_of(link):
    return link.split("#token=", 1)[1]


def join(project, admin, role, email, name, companies=()):
    """Invites and accepts; returns the person's API client, signed in, and their account ID."""
    body = {"email": email, "name": name, "role": role}
    if companies:
        body["companyCodes"] = list(companies)
    invitation = admin.json("POST", "/api/console/invitations", body, expect=201)
    client = project.api(PORTAL_HOST if role == "CUSTOMER" else CONSOLE_HOST)
    client.json("POST", "/api/public/invitations/accept",
                {"token": token_of(invitation["inviteLink"]), "password": PEOPLE_PASSWORD}, expect=204)
    return client, invitation["accountId"]


def content(seed, size):
    return random.Random(seed).randbytes(size)


def png(seed, width, height):
    """A PNG of noise, 8 bits a channel, RGB."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    noise = random.Random(seed)
    rows = b"".join(b"\x00" + noise.randbytes(width * 3) for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b""))


def fill(project):
    """Fills the installation; returns the admin's client, the attachments that must read back the same,
    and the accounts that have a profile photo."""
    admin = project.api(CONSOLE_HOST)
    admin.sign_in(ADMIN_EMAIL, ADMIN_PASSWORD)
    admin_id = admin.json("GET", "/api/console/me")["id"]
    for code, name in (("ACME", "Acme"), ("BETA", "Beta")):
        admin.json("POST", "/api/console/companies", {"code": code, "name": name, "legalName": name + " Ltd."},
                   expect=201)

    agent, agent_id = join(project, admin, "AGENT", "agent@sample.popdesk.test", "Sample Agent")
    admin.request("PUT", f"/api/console/companies/ACME/agents/{agent_id}", expect=200)
    customer, customer_id = join(project, admin, "CUSTOMER", "customer@sample.popdesk.test", "Sample Customer",
                                 ["ACME"])
    other, _ = join(project, admin, "CUSTOMER", "other@sample.popdesk.test", "Other Customer", ["BETA"])
    # An invitation nobody accepted.
    admin.json("POST", "/api/console/invitations", {"email": "pending@sample.popdesk.test", "name": "Pending",
                                                    "role": "CUSTOMER", "companyCodes": ["ACME"]}, expect=201)

    keys = []
    for title in ("Printer does not print", "Invoice is missing", "Sign-in page is slow"):
        issue = customer.json("POST", "/api/portal/issues",
                              {"companyCode": "ACME", "title": title, "content": "Steps to reproduce:\n1. Open it."},
                              expect=201)
        keys.append(issue["key"])
    keys.append(other.json("POST", "/api/portal/issues",
                           {"companyCode": "BETA", "title": "Question about the contract",
                            "content": "When does it renew?"},
                           expect=201)["key"])

    first = keys[0]
    customer.json("POST", f"/api/portal/issues/{first}/comments", {"content": "It still happens."}, expect=201)
    agent.json("POST", f"/api/console/issues/{first}/comments", {"content": "We are looking at it."}, expect=201)
    agent.json("POST", f"/api/console/issues/{first}/comments", {"content": "Internal: check the driver.",
                                                                    "internal": True}, expect=201)

    # A profile photo larger than the app stores, so what it keeps is its own scaled copy (account_photo).
    customer.upload("/api/portal/me/photo", "file", "photo.png", png(7, 300, 200))
    # A password reset link nobody used (password_reset). Its email is one of the mail rows waited for below.
    other.json("POST", "/api/public/password-reset/request", {"email": "other@sample.popdesk.test"}, expect=202)

    kept = []

    def upload(client, api, key, file_name, seed, size):
        data = content(seed, size)
        attachment = client.upload(f"{api}/issues/{key}/attachments", "file", file_name, data)
        return {"issue": key, "id": attachment["id"], "fileName": file_name, "size": size, "sha256": sha256(data)}

    kept.append(upload(customer, "/api/portal", first, "screenshot.png", 1, 20_000))
    # The longest file name allowed: 255 characters.
    kept.append(upload(customer, "/api/portal", keys[1], ("n" * 251) + ".txt", 2, 3_000))
    kept.append(upload(agent, "/api/console", first, "driver-log.txt", 3, 5_000))
    # Takes the total over 80% of the quota: the quota alert for every admin.
    kept.append(upload(customer, "/api/portal", keys[2], "large.bin", 4, 820_000))
    deleted = upload(customer, "/api/portal", keys[2], "deleted.bin", 5, 1_000)
    customer.request("DELETE", f"/api/portal/issues/{keys[2]}/attachments/{deleted['id']}", expect=204)
    purging = upload(customer, "/api/portal", keys[2], "purging.bin", 6, 1_000)

    # Mail rows of this run are sent (SMTP is off: one log line each) and deleted. Wait for that, so the
    # queue holds only the failed row added below when it is dumped.
    end = time.monotonic() + 60
    while project.sql("select count(*) from outbox") != "0":
        if time.monotonic() > end:
            raise HarnessError("The send queue did not empty within 60 seconds.")
        time.sleep(1)

    # States the API cannot reach on demand. An attachment in state purging: a delete whose file
    # removal failed (the daily cleanup ends it later).
    project.sql(f"update attachment set state = 'purging', file_name = null, deleted_at = now(), "
                f"deleted_by = {admin_id} where id = '{purging['id']}' and state = 'stored'")
    # A send queue row in state failed: an email given up after 24 hours of failures.
    project.sql(
        "insert into outbox (tenant_id, channel, kind, recipient_account_id, issue_id, comment_id, state, attempts, "
        "next_attempt_at, first_failed_at, failed_at, last_error_kind, created_at) "
        "select 1, 'MAIL', 'STAFF_COMMENT', i.reporter_id, i.id, "
        "(select max(c.id) from comment c where c.issue_id = i.id and not c.internal), 'failed', 25, "
        "now(), now() - interval '24 hours', now(), 'SMTP_REFUSED', now() - interval '24 hours' "
        f"from issue i join company co on co.id = i.company_id where co.code || '-' || i.number = '{first}'")
    return admin, kept, [customer_id]


def check_every_table_has_rows(project):
    tables = project.sql("select table_name from information_schema.tables where table_schema = current_schema() "
                         "and table_type = 'BASE TABLE' and table_name not in ('flyway_schema_history', "
                         "'spring_session', 'spring_session_attributes', 'tenant') order by 1").split()
    empty = [t for t in tables if project.sql(f'select count(*) from "{t}"') == "0"]
    if empty:
        raise HarnessError(f"The sample leaves these business tables empty: {empty}. Add data for them.")


def check_audit_chain(project):
    """The chain's one row (audit_chain, 1b) is filled by what the sample did, not only by the migration:
    its head is the last of the sample's audit rows, and every row has its place and hash."""
    rows = int(project.sql("select count(*) from audit_log"))
    head = project.sql("select head_seq || ' ' || head_hash from audit_chain").split()
    last = project.sql("select seq || ' ' || hash from audit_log order by seq desc limit 1").split()
    if rows == 0 or head != last or int(head[0]) != rows:
        raise HarnessError(f"The sample's audit chain does not end at its last audit row: {rows} rows, "
                           f"head {head}, last row {last}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    project = Project(args.project, args.image, out, app_env={
        "POPDESK_ADMIN_EMAIL": ADMIN_EMAIL, "POPDESK_ADMIN_PASSWORD": ADMIN_PASSWORD,
        "POPDESK_ATTACHMENT_QUOTA": QUOTA})
    try:
        identity = image_id(args.image)
        log(f"Starting {args.image} ({identity}) as {args.project}")
        project.up()
        if not project.wait_healthy("app", 600):
            raise HarnessError("The app did not become healthy:\n" + project.logs("app")[-4000:])
        admin, kept, with_photo = fill(project)
        check_every_table_has_rows(project)
        check_audit_chain(project)

        expected = {
            "issues": read_issues(admin),
            "attachments": kept,
            "outbox": read_outbox(project),
            "audit": read_audit(project),
            "photos": read_photos(admin, with_photo),
            "passwordResets": read_password_resets(project),
        }
        if not expected["outbox"]:
            raise HarnessError("The sample has no failed queue row.")
        if not expected["passwordResets"]:
            raise HarnessError("The sample has no password reset link.")

        project.compose("exec", "-T", "app", "popdesk", "backup", "/tmp/sample.dump")
        project.compose("cp", "app:/tmp/sample.dump", str(out / "sample.dump"))
        (out / "sample.image").write_text(identity + "\n", encoding="utf-8")
        (out / "sample.expected.json").write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8")
        log(f"Wrote the sample to {out}")
        return 0
    except Exception as e:  # noqa: BLE001 - any failure of the generator is exit code 3
        log(f"make-sample.py: {e}")
        return 3
    finally:
        project.down()
        project.override.unlink(missing_ok=True)


if __name__ == "__main__":
    sys.exit(main())
