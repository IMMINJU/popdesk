"""Shared parts of make-sample.py and upgrade-test.py (deploy/upgrade/README.md).

A Compose project of popdesk started from deploy/compose.yml the way an operator would, plus a
generated override that only makes it safe next to other projects: the image to test, a random host
port for app 8080, and volume names with the project name in front (so a real popdesk-db is never
touched). The environment of this process is passed on to docker compose.

Python 3 standard library only.
"""

import hashlib
import http.client
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RELEASE_COMPOSE = REPO / "deploy" / "compose.yml"

# The sample's people. The upgrade harness signs in as the admin to read the sample back, so these
# stay the same in every release.
ADMIN_EMAIL = "admin@sample.popdesk.test"
ADMIN_PASSWORD = "sample-Admin-Password-1"
PEOPLE_PASSWORD = "sample-People-Password-1"
DB_PASSWORD = "sample-Db-Password-1"
PORTAL_HOST = "portal.localhost"
CONSOLE_HOST = "console.localhost"

# Failed queue rows are deleted this long after they failed (configuration.md, D-39).
FAILED_ROWS_KEPT_DAYS = 7


class HarnessError(Exception):
    """The harness itself could not do its work (exit code 3)."""


def log(message):
    print(message, file=sys.stderr, flush=True)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


class Project:
    """One Compose project: deploy/compose.yml, extra files, and the generated override."""

    def __init__(self, name, image, work, extra_files=(), app_env=None):
        self.name = name
        self.image = image
        self.work = Path(work)
        self.work.mkdir(parents=True, exist_ok=True)
        self.files = [RELEASE_COMPOSE] + [Path(f).resolve() for f in extra_files]
        self.app_env = dict(app_env or {})
        self.override = self.work / f"{name}-override.yml"
        self.env = dict(os.environ)
        self.env.setdefault("POPDESK_DB_PASSWORD", DB_PASSWORD)
        self.started = False

    def compose(self, *args, check=True, input_bytes=None, timeout=600, override=True):
        command = ["docker", "compose", "-p", self.name]
        for f in self.files:
            command += ["-f", str(f)]
        if override:
            command += ["-f", str(self.override)]
        command += list(args)
        result = subprocess.run(command, input=input_bytes, capture_output=True, env=self.env, cwd=REPO,
                                timeout=timeout)
        if check and result.returncode != 0:
            raise HarnessError(f"{' '.join(command)} ended with {result.returncode}:\n"
                               f"{result.stdout.decode(errors='replace')}\n{result.stderr.decode(errors='replace')}")
        return result

    def config(self):
        return json.loads(self.compose("config", "--format", "json", override=False).stdout)

    def write_override(self):
        config = self.config()
        lines = ["services:", "  app:", f"    image: {json.dumps(self.image)}", "    ports: !override",
                 '      - "8080"']
        if self.app_env:
            lines.append("    environment:")
            for key, value in self.app_env.items():
                lines.append(f"      {key}: {json.dumps(str(value))}")
        volumes = config.get("volumes") or {}
        if volumes:
            lines.append("volumes:")
            for key in volumes:
                lines.append(f"  {key}:")
                lines.append(f"    name: {json.dumps(self.name + '-' + key.lower())}")
        self.override.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def up(self, *services):
        self.write_override()
        self.started = True
        self.compose("up", "-d", "--remove-orphans", *services, timeout=900)

    def down(self):
        if self.started:
            self.compose("down", "-v", "--remove-orphans", "--timeout", "20", check=False, timeout=300)

    def port(self, service, container_port):
        out = self.compose("port", service, str(container_port)).stdout.decode().strip()
        return int(out.splitlines()[0].rsplit(":", 1)[1])

    def container_state(self, service):
        cid = self.compose("ps", "-a", "-q", service).stdout.decode().strip()
        if not cid:
            return None
        result = subprocess.run(["docker", "inspect", cid.splitlines()[0]], capture_output=True, timeout=60)
        if result.returncode != 0:
            return None
        info = json.loads(result.stdout)[0]
        return {"state": info["State"], "restarts": info.get("RestartCount", 0)}

    def wait_healthy(self, service, timeout):
        """True once the service is healthy; False when it stops, restarts, turns unhealthy, or time runs out."""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            info = self.container_state(service)
            if info is not None:
                state = info["state"]
                health = (state.get("Health") or {}).get("Status")
                if health == "healthy":
                    return True
                if health == "unhealthy" or info["restarts"] > 0 or state.get("Status") in ("exited", "dead"):
                    return False
            time.sleep(2)
        return False

    def logs(self, service):
        result = self.compose("logs", "--no-color", "--no-log-prefix", service, check=False)
        return result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace")

    def sql(self, statement):
        """Runs SQL in the db container with psql and returns the output without a header."""
        result = self.compose("exec", "-T", "db", "psql", "-v", "ON_ERROR_STOP=1", "-X", "-q", "-A", "-t",
                              "-U", "popdesk", "-d", "popdesk", "-c", statement)
        return result.stdout.decode().strip()

    def sql_json(self, statement):
        """Runs a query whose one value is JSON text."""
        out = self.sql(statement)
        return json.loads(out) if out else None

    def api(self, host):
        return Api("127.0.0.1", self.port("app", 8080), host)


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path} answered {status}: {body[:500]!r}")
        self.status = status


class Api:
    """A browser of one host: cookies, the CSRF token, and an X-Forwarded-For of its own."""

    def __init__(self, address, port, host):
        self.address = address
        self.port = port
        self.host = host
        self.cookies = {}
        # The sign-in limit counts per client address; give each client its own (198.18.0.0/15).
        self.forwarded_for = f"198.18.{uuid.uuid4().int % 250}.{uuid.uuid4().int % 250 + 1}"

    def request(self, method, path, body=None, content_type=None, expect=None):
        headers = {"Host": self.host, "X-Forwarded-For": self.forwarded_for, "Accept": "*/*"}
        if method not in ("GET", "HEAD"):
            if "XSRF-TOKEN" not in self.cookies:
                self.request("GET", "/api/public/csrf")
            headers["X-XSRF-TOKEN"] = self.cookies["XSRF-TOKEN"]
        if self.cookies:
            headers["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if body is not None:
            headers["Content-Type"] = content_type
        connection = http.client.HTTPConnection(self.address, self.port, timeout=120)
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read()
            for header, value in response.getheaders():
                if header.lower() == "set-cookie":
                    name, _, rest = value.partition("=")
                    cookie_value = rest.split(";", 1)[0]
                    if "Max-Age=0" in value or cookie_value == "":
                        self.cookies.pop(name, None)
                    else:
                        self.cookies[name] = cookie_value
        finally:
            connection.close()
        if expect is not None and response.status != expect:
            raise ApiError(method, path, response.status, data.decode(errors="replace"))
        return response.status, data

    def json(self, method, path, value=None, expect=200):
        body = None if value is None else json.dumps(value).encode()
        _, data = self.request(method, path, body, "application/json", expect)
        return json.loads(data) if data else None

    def upload(self, path, field, file_name, content, expect=201):
        boundary = "popdesk-" + uuid.uuid4().hex
        body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; filename=\"{file_name}\"\r\n"
                f"Content-Type: application/octet-stream\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
        _, data = self.request("POST", path, body, f"multipart/form-data; boundary={boundary}", expect)
        return json.loads(data)

    def sign_in(self, email, password):
        self.request("POST", "/api/public/sign-in", json.dumps({"email": email, "password": password}).encode(),
                     "application/json", 204)


def paged(api, path):
    """Every item of a paged list."""
    items, page = [], 1
    while True:
        separator = "&" if "?" in path else "?"
        result = api.json("GET", f"{path}{separator}page={page}&size=100")
        items += result["items"]
        if page >= result["totalPages"]:
            return items
        page += 1


def image_id(image):
    result = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", image], capture_output=True,
                            timeout=60)
    if result.returncode != 0:
        raise HarnessError(f"The image {image} does not exist: {result.stderr.decode(errors='replace')}")
    return result.stdout.decode().strip()


# What the sample check reads back. make-sample.py writes it into sample.expected.json, and
# upgrade-test.py reads the same things from the upgraded installation and compares.

def read_issues(console):
    return sorted(issue["key"] for issue in paged(console, "/api/console/issues"))


def read_outbox(project):
    """Failed queue rows. The sample has no other rows when it is dumped (make-sample.py waits)."""
    return project.sql_json(
        "select coalesce(json_agg(json_build_object('id', id, 'channel', channel, 'kind', kind, 'state', state, "
        "'attempts', attempts, 'lastErrorKind', last_error_kind, 'failedAt', failed_at) order by id), '[]') "
        "from outbox where state = 'failed'")


def read_photos(console, account_ids):
    """The stored profile photo of each account, as the console serves it to an admin."""
    photos = []
    for account_id in account_ids:
        _, data = console.request("GET", f"/api/console/photos/{account_id}", expect=200)
        photos.append({"accountId": account_id, "sha256": sha256(data)})
    return photos


def read_password_resets(project):
    """Password reset links: whose, and from when to when. Never the token hash."""
    return project.sql_json(
        "select coalesce(json_agg(json_build_object('accountId', account_id, 'createdAt', created_at, "
        "'expiresAt', expires_at) order by account_id), '[]') from password_reset")


def read_audit(project):
    return project.sql_json(
        "select coalesce(json_agg(json_build_object('id', id, 'action', action, 'row', row_to_json(a)::text) "
        "order by id), '[]') from audit_log a")
