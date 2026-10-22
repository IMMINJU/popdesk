#!/usr/bin/env python3
"""Puts the load of a small team on a running popdesk behind its TLS proxy (deploy/soak/README.md).

    SOAK_ADMIN_PASSWORD=... python deploy/soak/soak.py --portal https://portal.localhost \\
        --console https://console.localhost --cacert root.crt --admin-email admin@example.test \\
        --mailpit http://127.0.0.1:8025 --project popdesk --compose-file deploy/compose.yml \\
        --duration 24h --out soak-out

Makes 3 customer companies, 4 agents, and 30 customers through the API (or takes them as they are on a
second start), then sends about one request a second in bursts like a working day, each person with
their own session. Once an hour, and at the end, it writes one line to OUT/hourly.jsonl: latency per
kind of request, status codes, app metrics, container memory, database size, and log lines.

Python 3 standard library only. Exit code 0 when no request got a 5xx or no answer; 1 when one did;
3 when the tool itself failed (setup, arguments).
"""

import argparse
import collections
import datetime
import hashlib
import http.client
import json
import os
import random
import re
import signal
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

DOMAIN = "soak.popdesk.test"
COMPANIES = (("SOAKA", "Soak Alpha"), ("SOAKB", "Soak Bravo"), ("SOAKC", "Soak Charlie"))
# Agent number -> the companies it works on. The fourth covers all three.
AGENTS = {1: ("SOAKA",), 2: ("SOAKB",), 3: ("SOAKC",), 4: ("SOAKA", "SOAKB", "SOAKC")}
CUSTOMERS = 30
# This customer is the manager of the second company.
MANAGER = 11

# Words of titles and content, and the 1 to 3 character search terms that find them.
WORDS = ["프린터", "결제", "로그인", "계약서", "견적", "배송", "환불", "모니터", "느림", "오류", "접속", "메일",
         "printer", "invoice", "login", "scanner", "slow", "error", "report", "network", "update", "license"]
TERMS = ["프", "결", "로", "견", "오", "프린", "결제", "로그", "모니", "접속", "프린터", "계약서", "모니터",
         "p", "i", "s", "in", "sc", "er", "inv", "log", "net", "upd", "zz", "없음"]


class SoakError(Exception):
    """The tool could not do its work (exit code 3)."""


def log(message):
    print(f"{datetime.datetime.now().strftime('%H:%M:%S')} {message}", file=sys.stderr, flush=True)


def masked(email):
    """The address with everything after its first character masked: a***@example.test (PII-2)."""
    at = email.rfind("@")
    return (email[:1] or "") + "***" + (email[at:] if at >= 0 else "")


def duration(text):
    match = re.fullmatch(r"(\d+)([smhd]?)", text.strip())
    if not match:
        raise argparse.ArgumentTypeError(f"not a duration: {text} (use 600, 10m, 24h)")
    return int(match.group(1)) * {"": 1, "s": 1, "m": 60, "h": 3600, "d": 86400}[match.group(2)]


def secret(env, file_option):
    if file_option:
        return Path(file_option).read_text(encoding="utf-8").rstrip("\r\n")
    value = os.environ.get(env)
    if not value:
        raise SoakError(f"Give the password in {env} or as a file.")
    return value


# --- HTTP ------------------------------------------------------------------------------------------

class Target:
    """One app address: scheme, host, port, the address to connect to, and the TLS context."""

    def __init__(self, url, connect, context):
        parts = urllib.parse.urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.hostname:
            raise SoakError(f"Not an http or https address: {url}")
        self.scheme = parts.scheme
        self.host = parts.hostname
        self.port = parts.port or (443 if self.scheme == "https" else 80)
        # Windows does not resolve *.localhost; browsers do it themselves.
        self.address = connect or ("127.0.0.1" if self.host == "localhost" or self.host.endswith(".localhost")
                                   else self.host)
        self.context = context
        self.host_header = self.host if parts.port is None else f"{self.host}:{self.port}"

    def connection(self):
        if self.scheme == "http":
            return http.client.HTTPConnection(self.address, self.port, timeout=120)
        return _TlsConnection(self, timeout=120)


class _TlsConnection(http.client.HTTPSConnection):
    """Connects to the target's address, and checks the certificate for its host name."""

    def __init__(self, target, timeout):
        super().__init__(target.host, target.port, timeout=timeout, context=target.context)
        self._target = target

    def connect(self):
        sock = socket.create_connection((self._target.address, self.port), self.timeout)
        self.sock = self._target.context.wrap_socket(sock, server_hostname=self._target.host)


class Answer:
    def __init__(self, status, data, headers):
        self.status = status
        self.data = data
        self.headers = headers

    def json(self):
        return json.loads(self.data) if self.data else None


class Client:
    """A browser of one person in one app: its cookies and the CSRF token. Not shared between threads
    without holding `lock`."""

    def __init__(self, target, recorder):
        self.target = target
        self.recorder = recorder
        self.cookies = {}
        self.lock = threading.Lock()

    def request(self, method, path, body=None, content_type=None, kind=None, shape=None, headers=None):
        """Sends one request. Records it when `kind` is given; `shape` is the path with IDs taken out."""
        sent = {"Host": self.target.host_header, "Accept": "application/json", **(headers or {})}
        if method not in ("GET", "HEAD"):
            if "XSRF-TOKEN" not in self.cookies:
                self.request("GET", "/api/public/csrf", kind="public.csrf", shape="/api/public/csrf")
            sent["X-XSRF-TOKEN"] = self.cookies.get("XSRF-TOKEN", "")
        if self.cookies:
            sent["Cookie"] = "; ".join(f"{k}={v}" for k, v in self.cookies.items())
        if body is not None:
            sent["Content-Type"] = content_type
        started = time.monotonic()
        connection = self.target.connection()
        try:
            connection.request(method, path, body=body, headers=sent)
            response = connection.getresponse()
            data = response.read()
            headers_in = response.getheaders()
        except (OSError, http.client.HTTPException) as e:
            if kind:
                self.recorder.add(kind, time.monotonic() - started, 0, shape or path, type(e).__name__)
            raise
        finally:
            connection.close()
        for header, value in headers_in:
            if header.lower() == "set-cookie":
                name, _, rest = value.partition("=")
                cookie_value = rest.split(";", 1)[0]
                if "Max-Age=0" in value or cookie_value == "":
                    self.cookies.pop(name, None)
                else:
                    self.cookies[name] = cookie_value
        if kind:
            self.recorder.add(kind, time.monotonic() - started, response.status, shape or path)
        return Answer(response.status, data, dict((k.lower(), v) for k, v in headers_in))

    def json(self, method, path, value=None, kind=None, shape=None, headers=None):
        body = None if value is None else json.dumps(value).encode()
        return self.request(method, path, body, "application/json", kind, shape, headers)

    def sign_in(self, email, password):
        """Signs in; waits out the sign-in limit per address (20 in 60 seconds) instead of failing."""
        body = json.dumps({"email": email, "password": password}).encode()
        for _ in range(20):
            answer = self.request("POST", "/api/public/sign-in", body, "application/json", "public.sign-in",
                                  "/api/public/sign-in")
            if answer.status == 204:
                return
            if answer.status == 429:
                time.sleep(int(answer.headers.get("retry-after", "10")) + random.random())
                continue
            raise SoakError(f"Sign-in of {masked(email)} answered {answer.status}.")
        raise SoakError(f"Sign-in of {masked(email)} kept answering 429.")


def multipart(parts):
    """parts: (field, file name or None, content type, bytes)."""
    boundary = "soak-" + uuid.uuid4().hex
    body = b""
    for field, file_name, content_type, data in parts:
        disposition = f'form-data; name="{field}"' + (f'; filename="{file_name}"' if file_name else "")
        body += (f"--{boundary}\r\nContent-Disposition: {disposition}\r\nContent-Type: {content_type}\r\n\r\n"
                 .encode() + data + b"\r\n")
    return body + f"--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


# --- Recording -------------------------------------------------------------------------------------

class Recorder:
    """Every request of the current hour, in memory."""

    def __init__(self):
        self.lock = threading.Lock()
        self.rows = []
        self.server_errors = 0
        self.no_answer = 0

    def add(self, kind, seconds, status, shape, error=None):
        with self.lock:
            self.rows.append((kind, seconds, status, shape, error))
            if status >= 500:
                self.server_errors += 1
            elif status == 0:
                self.no_answer += 1

    def take(self):
        with self.lock:
            rows, self.rows = self.rows, []
        return rows


def percentile(sorted_values, fraction):
    if not sorted_values:
        return None
    index = min(len(sorted_values) - 1, max(0, int(round(fraction * len(sorted_values) + 0.5)) - 1))
    return sorted_values[index]


def summarize(rows):
    by_kind = collections.defaultdict(list)
    statuses = collections.Counter()
    server_errors = collections.Counter()
    client_errors = collections.Counter()
    no_answer = collections.Counter()
    for kind, seconds, status, shape, error in rows:
        by_kind[kind].append(seconds * 1000)
        statuses[str(status)] += 1
        if status >= 500:
            server_errors[f"{status} {shape}"] += 1
        elif status >= 400:
            client_errors[f"{status} {kind}"] += 1
        elif status == 0:
            no_answer[f"{error} {shape}"] += 1
    kinds = {}
    for kind, values in sorted(by_kind.items()):
        values.sort()
        kinds[kind] = {"count": len(values), "p50Ms": round(percentile(values, 0.5), 1),
                       "p95Ms": round(percentile(values, 0.95), 1), "maxMs": round(values[-1], 1)}
    every = sorted(s * 1000 for _, s, _, _, _ in rows)
    return {"requests": len(rows),
            "all": {"p50Ms": round(percentile(every, 0.5), 1) if every else None,
                    "p95Ms": round(percentile(every, 0.95), 1) if every else None,
                    "maxMs": round(every[-1], 1) if every else None},
            "kinds": kinds, "statuses": dict(sorted(statuses.items())),
            "serverErrors": dict(server_errors), "clientErrors": dict(sorted(client_errors.items())),
            "noAnswer": dict(no_answer)}


class Installation:
    """What the tool reads from the host: Compose for metrics, docker stats, the database size, logs."""

    def __init__(self, project, files, env_file):
        self.base = ["docker", "compose"] + (["-p", project] if project else [])
        for f in files:
            self.base += ["-f", f]
        if env_file:
            self.base += ["--env-file", env_file]

    def run(self, *args, timeout=120):
        result = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout)
        if result.returncode != 0:
            raise SoakError(f"docker compose {' '.join(args[:3])} ended with {result.returncode}: "
                            f"{result.stderr.decode(errors='replace')[-300:]}")
        return result.stdout

    def metrics(self):
        """The app's Prometheus text from the management port 8081, read inside the app container with
        bash's /dev/tcp, the same way the Compose health check reads readiness."""
        script = ("exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/prometheus HTTP/1.0\\r\\n"
                  "Host: localhost\\r\\n\\r\\n' >&3 && cat <&3")
        text = self.run("exec", "-T", "app", "bash", "-c", script).decode(errors="replace")
        values = collections.defaultdict(float)
        found = set()
        for line in text.splitlines():
            if not line or line.startswith("#"):
                continue
            match = re.match(r"([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)", line)
            if not match:
                continue
            name, labels, value = match.group(1), match.group(2) or "", match.group(3)
            try:
                number = float(value)
            except ValueError:
                continue
            key = None
            if name == "jvm_memory_used_bytes" and 'area="heap"' in labels:
                key = "heapUsedBytes"
            elif name == "jvm_memory_max_bytes" and 'area="heap"' in labels and number > 0:
                key = "heapMaxBytes"
            elif name == "jvm_gc_live_data_size_bytes":
                key = "oldGenAfterGcBytes"
            elif name == "jvm_memory_usage_after_gc_percent" and 'area="heap"' in labels:
                key = "heapAfterGcPercent"
            elif name in ("process_resident_memory_bytes", "process_memory_rss_bytes"):
                key = "processResidentBytes"
            elif name == "jvm_memory_committed_bytes":
                key = "jvmCommittedBytes"
            elif name == "http_server_requests_seconds_count":
                key = "httpServerRequests"
            elif name == "jvm_threads_live_threads":
                key = "liveThreads"
            if key:
                values[key] += number
                found.add(key)
        status = self.run("exec", "-T", "app", "cat", "/proc/1/status").decode(errors="replace")
        rss = re.search(r"^VmRSS:\s+(\d+) kB", status, re.M)
        if rss:
            # The image runs java as process 1.
            values["processResidentBytes"] = int(rss.group(1)) * 1024
            found.add("processResidentBytes")
        result = {k: values[k] for k in sorted(found)}
        if "heapAfterGcPercent" in result:
            result["heapAfterGcPercent"] = round(result["heapAfterGcPercent"] * 100, 2)
        return result

    def container_memory(self):
        ids = {}
        for service in ("app", "db"):
            cid = self.run("ps", "-q", service).decode().strip().splitlines()
            if cid:
                ids[cid[0]] = service
        if not ids:
            return {}
        result = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{json .}}"] + list(ids),
                                capture_output=True, timeout=120)
        memory = {}
        for line in result.stdout.decode(errors="replace").splitlines():
            row = json.loads(line)
            service = next((s for c, s in ids.items() if c.startswith(row.get("ID", "")) or row.get("ID", "")
                            .startswith(c[:12])), row.get("Name"))
            memory[service] = {"memUsage": row.get("MemUsage"), "memBytes": size_bytes(row.get("MemUsage", "")),
                               "cpu": row.get("CPUPerc")}
        return memory

    def database_size(self):
        out = self.run("exec", "-T", "db", "psql", "-U", "popdesk", "-d", "popdesk", "-X", "-A", "-t", "-c",
                       "select pg_database_size(current_database())")
        return int(out.decode().strip())

    def log_lines(self, since=None):
        args = ["logs", "--no-color", "--no-log-prefix"] + (["--since", since] if since else []) + ["app"]
        result = subprocess.run(self.base + args, capture_output=True, timeout=600)
        return (result.stdout + result.stderr).count(b"\n")


def size_bytes(mem_usage):
    match = re.match(r"\s*([\d.]+)\s*([KMGT]?i?B)", mem_usage or "")
    if not match:
        return None
    factor = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "TiB": 1024 ** 4,
              "kB": 1000, "KB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3, "TB": 1000 ** 4}.get(match.group(2), 1)
    return int(float(match.group(1)) * factor)


# --- Setup -----------------------------------------------------------------------------------------

class Person:
    def __init__(self, number, email, name, role, companies, client):
        self.number = number
        self.email = email
        self.name = name
        self.role = role
        self.companies = companies
        self.client = client
        self.account_id = None
        self.keys = []


class Mailpit:
    """Reads invitation links out of the mail that Mailpit caught."""

    def __init__(self, url):
        self.url = url.rstrip("/")

    def get(self, path):
        with urllib.request.urlopen(self.url + path, timeout=30) as response:
            return json.loads(response.read())

    def invite_link(self, email, after, timeout=120):
        """The link of the newest invitation to `email` that came after `after` (a UTC datetime)."""
        end = time.monotonic() + timeout
        query = urllib.parse.quote(f'to:"{email}"')
        while time.monotonic() < end:
            messages = self.get(f"/api/v1/search?query={query}&limit=20").get("messages") or []
            for message in sorted(messages, key=lambda m: m.get("Created", ""), reverse=True):
                created = datetime.datetime.fromisoformat(message["Created"].replace("Z", "+00:00"))
                if created < after:
                    break
                text = self.get(f"/api/v1/message/{message['ID']}").get("Text") or ""
                match = re.search(r"(\S+)/invite#token=([A-Za-z0-9_\-]+)", text)
                if match:
                    return match.group(1), match.group(2)
            time.sleep(2)
        raise SoakError(f"No invitation email to {masked(email)} came within {timeout} seconds.")


def paged(client, path, kind):
    items, page = [], 1
    while True:
        separator = "&" if "?" in path else "?"
        answer = client.request("GET", f"{path}{separator}page={page}&size=100", kind=kind,
                                shape=path.split("?", 1)[0])
        if answer.status != 200:
            raise SoakError(f"GET {path.split('?', 1)[0]} answered {answer.status}.")
        result = answer.json()
        items += result["items"]
        if page >= result["totalPages"]:
            return items
        page += 1


def expect(answer, statuses, what):
    if answer.status not in statuses:
        raise SoakError(f"{what} answered {answer.status}: {answer.data[:300]!r}")
    return answer


class Setup:
    def __init__(self, args, portal, console, recorder, admin_password, people_password):
        self.args = args
        self.portal = portal
        self.console = console
        self.recorder = recorder
        self.people_password = people_password
        self.mailpit = Mailpit(args.mailpit) if args.mailpit else None
        self.admin = Client(console, recorder)
        self.admin.sign_in(args.admin_email, admin_password)
        self.link_schemes = set()

    def run(self):
        admin = self.admin
        existing = {c["code"] for c in paged(admin, "/api/console/companies", "setup")}
        for code, name in COMPANIES:
            if code not in existing:
                expect(admin.json("POST", "/api/console/companies", {"code": code, "name": name,
                                                                     "legalName": name + " Ltd."}),
                       (201,), f"Creating company {code}")
                log(f"Made company {code}")
        accounts = {a["email"]: a for a in paged(admin, f"/api/console/accounts?q={DOMAIN}", "setup")}
        pending = {i["email"]: i for i in paged(admin, "/api/console/invitations", "setup")}

        people = []
        for number, companies in AGENTS.items():
            people.append(Person(number, f"agent{number}@{DOMAIN}", f"Soak Agent {number}", "AGENT", companies,
                                 Client(self.console, self.recorder)))
        for number in range(1, CUSTOMERS + 1):
            company = COMPANIES[(number - 1) % len(COMPANIES)][0]
            people.append(Person(number, f"customer{number:02d}@{DOMAIN}", f"Soak Customer {number}", "CUSTOMER",
                                 (company,), Client(self.portal, self.recorder)))
        for person in people:
            self.join(person, accounts.get(person.email), pending.get(person.email))
        for person in people:
            if person.role == "AGENT":
                for code in person.companies:
                    expect(admin.request("PUT", f"/api/console/companies/{code}/agents/{person.account_id}",
                                         kind="setup"), (200, 204), "Assigning an agent")
            elif person.number == MANAGER:
                expect(admin.request("PUT", f"/api/console/companies/{person.companies[0]}/customers/"
                                            f"{person.account_id}/manager", kind="setup"),
                       (200, 204), "Making a manager")
        return people

    def join(self, person, account, invitation):
        if account and account["status"] == "ACTIVE":
            person.account_id = account["id"]
            person.client.sign_in(person.email, self.people_password)
            return
        if account and account["status"] == "SUSPENDED":
            raise SoakError(f"{masked(person.email)} is suspended; reactivate it or use a new installation.")
        after = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)
        if account and invitation and not invitation.get("cancelled"):
            # Invited by an earlier run that stopped before the person accepted: send it again.
            person.account_id = account["id"]
            expect(self.admin.request("POST", f"/api/console/invitations/{account['id']}/resend", kind="setup"),
                   (200, 204), "Sending an invitation again")
            link = None
        else:
            body = {"email": person.email, "name": person.name, "role": person.role}
            if person.role == "CUSTOMER":
                body["companyCodes"] = list(person.companies)
            result = expect(self.admin.json("POST", "/api/console/invitations", body, kind="setup"), (201,),
                            "Inviting").json()
            person.account_id = result["accountId"]
            link = result.get("inviteLink")
        if link:
            # SMTP is off: the console shows the link.
            base, token = link.split("/invite#token=", 1)
        elif self.mailpit:
            base, token = self.mailpit.invite_link(person.email, after)
        else:
            raise SoakError("Invitations go out by email; give --mailpit to read them.")
        self.link_schemes.add(urllib.parse.urlsplit(base).scheme)
        expect(person.client.json("POST", "/api/public/invitations/accept",
                                  {"token": token, "password": self.people_password}, kind="setup"),
               (204,), f"Accepting the invitation of {masked(person.email)}")
        if person.client.request("GET", f"/api/{'portal' if person.role == 'CUSTOMER' else 'console'}/me",
                                 kind="setup").status != 200:
            person.client.sign_in(person.email, self.people_password)
        log(f"{masked(person.email)} joined")


# --- Load ------------------------------------------------------------------------------------------

def text(rng, words):
    return " ".join(rng.choice(WORDS) for _ in range(words))


class Load:
    def __init__(self, args, people, recorder):
        self.args = args
        self.rng = random.Random(args.seed)
        self.rng_lock = threading.Lock()
        self.recorder = recorder
        self.customers = [p for p in people if p.role == "CUSTOMER"]
        self.agents = [p for p in people if p.role == "AGENT"]
        self.keys_lock = threading.Lock()
        # Issue keys by company, as the load learns them.
        self.keys = collections.defaultdict(list)
        self.attachment = random.Random(args.seed).randbytes(100_000)
        self.actions = [
            (12, self.customer_list), (8, self.customer_search), (12, self.customer_view),
            (5, self.customer_file), (6, self.customer_reply), (1, self.customer_close),
            (10, self.staff_list), (8, self.staff_search), (12, self.staff_view),
            (6, self.agent_comment), (4, self.agent_status), (3, self.agent_assign),
            (10, self.read_inbox),
        ]
        self.weights = [w for w, _ in self.actions]

    def random(self):
        with self.rng_lock:
            return random.Random(self.rng.getrandbits(64))

    def learn(self, items):
        with self.keys_lock:
            for item in items:
                code = item["key"].rsplit("-", 1)[0]
                if item["key"] not in self.keys[code]:
                    self.keys[code].append(item["key"])
                    del self.keys[code][:-300]

    def learn_own(self, customer, items):
        """A customer comments on and closes only the issues they reported."""
        with self.keys_lock:
            for item in items:
                if item["key"] not in customer.keys and item["status"] != "CLOSED":
                    customer.keys.append(item["key"])
            del customer.keys[:-50]

    def some_key(self, rng, companies):
        with self.keys_lock:
            keys = [k for c in companies for k in self.keys[c][-100:]]
        return rng.choice(keys) if keys else None

    def seed_keys(self):
        for customer in self.customers:
            answer = customer.client.request("GET", "/api/portal/issues?mine=true&size=100",
                                             kind="portal.issues.list", shape="/api/portal/issues")
            if answer.status == 200:
                self.learn_own(customer, answer.json()["items"])
        for agent in self.agents:
            answer = agent.client.request("GET", "/api/console/issues?size=100", kind="console.issues.list",
                                          shape="/api/console/issues")
            if answer.status == 200:
                self.learn(answer.json()["items"])

    def one(self):
        """Picks one action and one person, and runs it while holding that person's client."""
        rng = self.random()
        action = rng.choices(self.actions, self.weights)[0][1]
        try:
            action(rng)
        except (OSError, http.client.HTTPException):
            pass  # Recorded as no answer.
        except Exception as e:  # noqa: BLE001 - a bug in one action must not stop the run
            log(f"An action failed in the tool: {type(e).__name__}")

    def person(self, rng, people):
        person = rng.choice(people)
        person.client.lock.acquire()
        return person

    # Customers.

    def customer_list(self, rng):
        c = self.person(rng, self.customers)
        try:
            mine = rng.random() < 0.3
            answer = c.client.request("GET", f"/api/portal/issues{'?mine=true' if mine else ''}",
                                      kind="portal.issues.list", shape="/api/portal/issues")
            if answer.status == 200:
                self.learn(answer.json()["items"])
                if mine:
                    self.learn_own(c, answer.json()["items"])
        finally:
            c.client.lock.release()

    def customer_search(self, rng):
        c = self.person(rng, self.customers)
        try:
            term = rng.choice(TERMS)
            c.client.request("GET", f"/api/portal/issues?q={urllib.parse.quote(term)}",
                             kind=f"portal.issues.search{len(term)}", shape="/api/portal/issues?q")
        finally:
            c.client.lock.release()

    def customer_view(self, rng):
        c = self.person(rng, self.customers)
        try:
            key = self.some_key(rng, c.companies)
            if key:
                c.client.request("GET", f"/api/portal/issues/{key}", kind="portal.issue.view",
                                 shape="/api/portal/issues/{key}")
                c.client.request("GET", f"/api/portal/issues/{key}/comments", kind="portal.comments.list",
                                 shape="/api/portal/issues/{key}/comments")
        finally:
            c.client.lock.release()

    def customer_file(self, rng):
        c = self.person(rng, self.customers)
        try:
            issue = {"companyCode": c.companies[0], "title": text(rng, 3), "content": text(rng, rng.randint(5, 80))}
            if rng.random() < 0.2:
                body, content_type = multipart([
                    ("issue", None, "application/json", json.dumps(issue).encode()),
                    ("files", f"log-{rng.randint(1, 999)}.bin", "application/octet-stream",
                     self.attachment[:rng.randint(80_000, 100_000)])])
                answer = c.client.request("POST", "/api/portal/issues", body, content_type,
                                          kind="portal.issue.file+attachment", shape="/api/portal/issues")
            else:
                answer = c.client.json("POST", "/api/portal/issues", issue, kind="portal.issue.file",
                                       shape="/api/portal/issues")
            if answer.status == 201:
                self.learn([answer.json()])
                self.learn_own(c, [answer.json()])
        finally:
            c.client.lock.release()

    def customer_reply(self, rng):
        c = self.person(rng, self.customers)
        try:
            with self.keys_lock:
                key = rng.choice(c.keys) if c.keys else None
            if key:
                c.client.json("POST", f"/api/portal/issues/{key}/comments", {"content": text(rng, 12)},
                              kind="portal.comment.add", shape="/api/portal/issues/{key}/comments")
        finally:
            c.client.lock.release()

    def customer_close(self, rng):
        c = self.person(rng, self.customers)
        try:
            answer = c.client.request("GET", "/api/portal/issues?mine=true&status=RESOLVED",
                                      kind="portal.issues.list", shape="/api/portal/issues")
            items = answer.json()["items"] if answer.status == 200 else []
            if items:
                key = rng.choice(items)["key"]
                if c.client.request("POST", f"/api/portal/issues/{key}/close", kind="portal.issue.close",
                                    shape="/api/portal/issues/{key}/close").status == 200:
                    with self.keys_lock:
                        if key in c.keys:
                            c.keys.remove(key)
        finally:
            c.client.lock.release()

    # Staff.

    def staff_list(self, rng):
        a = self.person(rng, self.agents)
        try:
            query = rng.choice(["", "?status=RECEIVED&status=CONFIRMED", f"?companyCode={rng.choice(a.companies)}",
                                "?sort=dueDate", "?assignee=me"])
            answer = a.client.request("GET", f"/api/console/issues{query}", kind="console.issues.list",
                                      shape="/api/console/issues")
            if answer.status == 200:
                self.learn(answer.json()["items"])
        finally:
            a.client.lock.release()

    def staff_search(self, rng):
        a = self.person(rng, self.agents)
        try:
            term = rng.choice(TERMS)
            a.client.request("GET", f"/api/console/issues?q={urllib.parse.quote(term)}",
                             kind=f"console.issues.search{len(term)}", shape="/api/console/issues?q")
        finally:
            a.client.lock.release()

    def staff_view(self, rng):
        a = self.person(rng, self.agents)
        try:
            key = self.some_key(rng, a.companies)
            if key:
                a.client.request("GET", f"/api/console/issues/{key}", kind="console.issue.view",
                                 shape="/api/console/issues/{key}")
                a.client.request("GET", f"/api/console/issues/{key}/comments", kind="console.comments.list",
                                 shape="/api/console/issues/{key}/comments")
        finally:
            a.client.lock.release()

    def agent_comment(self, rng):
        a = self.person(rng, self.agents)
        try:
            key = self.some_key(rng, a.companies)
            if key:
                a.client.json("POST", f"/api/console/issues/{key}/comments",
                              {"content": text(rng, 15), "internal": rng.random() < 0.25},
                              kind="console.comment.add", shape="/api/console/issues/{key}/comments")
        finally:
            a.client.lock.release()

    NEXT = {"RECEIVED": "CONFIRMED", "CONFIRMED": "IN_PROGRESS", "IN_PROGRESS": "RESOLVED"}

    def agent_status(self, rng):
        a = self.person(rng, self.agents)
        try:
            key = self.some_key(rng, a.companies)
            if not key:
                return
            answer = a.client.request("GET", f"/api/console/issues/{key}", kind="console.issue.view",
                                      shape="/api/console/issues/{key}")
            following = self.NEXT.get(answer.json()["status"]) if answer.status == 200 else None
            if following:
                a.client.json("PUT", f"/api/console/issues/{key}/status", {"status": following},
                              kind="console.issue.status", shape="/api/console/issues/{key}/status")
        finally:
            a.client.lock.release()

    def agent_assign(self, rng):
        a = self.person(rng, self.agents)
        try:
            key = self.some_key(rng, a.companies)
            if key:
                a.client.json("PUT", f"/api/console/issues/{key}/assignee", {"accountId": a.account_id},
                              kind="console.issue.assign", shape="/api/console/issues/{key}/assignee")
        finally:
            a.client.lock.release()

    # Both.

    def read_inbox(self, rng):
        p = self.person(rng, self.customers + self.agents)
        try:
            app = "portal" if p.role == "CUSTOMER" else "console"
            p.client.request("GET", f"/api/{app}/notifications/unread-count", kind=f"{app}.inbox.count",
                             shape=f"/api/{app}/notifications/unread-count")
            answer = p.client.request("GET", f"/api/{app}/notifications", kind=f"{app}.inbox.list",
                                      shape=f"/api/{app}/notifications")
            if answer.status == 200 and rng.random() < 0.3:
                unread = [n for n in answer.json()["items"] if not n.get("readAt")]
                if unread:
                    p.client.request("POST", f"/api/{app}/notifications/{rng.choice(unread)['id']}/read",
                                     kind=f"{app}.inbox.read", shape=f"/api/{app}/notifications/{{id}}/read")
        finally:
            p.client.lock.release()

    def keep_signed_in(self, people, password):
        """Signs a person in again when a session ended (a 401 on /me)."""
        for p in people:
            with p.client.lock:
                app = "portal" if p.role == "CUSTOMER" else "console"
                if p.client.request("GET", f"/api/{app}/me").status == 401:
                    p.client.cookies.clear()
                    p.client.sign_in(p.email, password)
                    log(f"{masked(p.email)} signed in again")


class Bursts:
    """The request rate over time: a base rate with bursts like a working day. On average about
    `rate` requests a second."""

    def __init__(self, rate, rng):
        self.rng = rng
        # Bursts take about 15% of the time at 4 times the base rate: 0.85 + 0.15 * 4 = 1.45.
        self.base = rate / 1.45
        self.burst_until = 0.0
        self.next_burst = time.monotonic() + rng.expovariate(1 / 600)

    def current(self):
        now = time.monotonic()
        if now >= self.next_burst:
            self.burst_until = now + self.rng.uniform(60, 180)
            self.next_burst = self.burst_until + self.rng.expovariate(1 / 600)
        return self.base * (4 if now < self.burst_until else 1)


# --- Main ------------------------------------------------------------------------------------------

def snapshot(installation, recorder, started_at, hour_start, log_since):
    line = {"time": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
            "from": hour_start, "elapsedSeconds": round(time.monotonic() - started_at)}
    line.update(summarize(recorder.take()))
    for key, read in (("app", installation.metrics), ("containers", installation.container_memory),
                      ("dbSizeBytes", installation.database_size), ("appLogLines", installation.log_lines)):
        try:
            line[key] = read()
        except Exception as e:  # noqa: BLE001 - a missing reading must not stop the run
            line[key] = None
            log(f"Could not read {key}: {e}")
    try:
        line["appLogLinesThisHour"] = installation.log_lines(log_since)
    except Exception:  # noqa: BLE001
        line["appLogLinesThisHour"] = None
    return line


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--portal", required=True, help="the portal address, e.g. https://portal.localhost")
    parser.add_argument("--console", required=True, help="the console address, e.g. https://console.localhost")
    parser.add_argument("--cacert", help="the CA certificate of the proxy (Caddy's root.crt for tls internal)")
    parser.add_argument("--insecure", action="store_true", help="do not check the TLS certificate")
    parser.add_argument("--connect", help="connect to this address instead of resolving the host names "
                                          "(*.localhost connects to 127.0.0.1 anyway)")
    parser.add_argument("--admin-email", required=True)
    parser.add_argument("--admin-password-file", help="else SOAK_ADMIN_PASSWORD")
    parser.add_argument("--people-password-file", help="else SOAK_PEOPLE_PASSWORD, else one made from the admin's")
    parser.add_argument("--mailpit", help="the Mailpit address, e.g. http://127.0.0.1:8025, to read invitations")
    parser.add_argument("--project", help="the Compose project name")
    parser.add_argument("--compose-file", action="append", default=[], help="a Compose file, more than once")
    parser.add_argument("--env-file", help="the .env file of the Compose project")
    parser.add_argument("--duration", type=duration, default=duration("24h"), help="600, 10m, 24h (default 24h)")
    parser.add_argument("--rate", type=float, default=1.0, help="average requests a second (default 1)")
    parser.add_argument("--interval", type=duration, default=duration("1h"),
                        help="time between lines of hourly.jsonl (default 1h)")
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    if args.cacert and args.insecure:
        parser.error("--cacert and --insecure exclude each other")

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    hourly = out / "hourly.jsonl"

    recorder = Recorder()
    try:
        context = ssl.create_default_context(cafile=args.cacert)
        if args.insecure:
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
        portal = Target(args.portal, args.connect, context)
        console = Target(args.console, args.connect, context)
        admin_password = secret("SOAK_ADMIN_PASSWORD", args.admin_password_file)
        if args.people_password_file or os.environ.get("SOAK_PEOPLE_PASSWORD"):
            people_password = secret("SOAK_PEOPLE_PASSWORD", args.people_password_file)
        else:
            people_password = "Soak-" + hashlib.sha256(("soak:" + admin_password).encode()).hexdigest()[:24]
        installation = Installation(args.project, args.compose_file, args.env_file)
        installation.run("ps", "-q", "app")

        setup = Setup(args, portal, console, recorder, admin_password, people_password)
        people = setup.run()
        if setup.link_schemes:
            log(f"Invitation links used: {', '.join(sorted(setup.link_schemes))}")
    except (SoakError, OSError, ssl.SSLError, http.client.HTTPException, subprocess.SubprocessError) as e:
        log(f"soak.py: {e}")
        return 3

    # From here on Ctrl-C (and SIGTERM, or Ctrl-Break on Windows) ends the run with a last line.
    stop = threading.Event()
    for name in ("SIGINT", "SIGTERM", "SIGBREAK"):
        if hasattr(signal, name):
            signal.signal(getattr(signal, name), lambda *_: stop.set())
    load = Load(args, people, recorder)
    load.seed_keys()
    recorder.take()  # Setup is not part of the first hour.
    log(f"Load of about {args.rate} requests a second for {args.duration} seconds; lines go to {hourly}")

    started_at = time.monotonic()
    end = started_at + args.duration
    next_line = started_at + args.interval
    next_session_check = started_at + 900
    hour_start = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    bursts = Bursts(args.rate, random.Random(args.seed + 1))
    arrivals = random.Random(args.seed + 2)
    pool = []
    pool_lock = threading.Lock()

    def write_line():
        nonlocal hour_start
        line = snapshot(installation, recorder, started_at, hour_start, hour_start)
        hour_start = line["time"]
        with hourly.open("a", encoding="utf-8") as f:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")
        log(f"Wrote a line: {line['requests']} requests, p95 {line['all']['p95Ms']} ms, "
            f"5xx {sum(line['serverErrors'].values())}, no answer {sum(line['noAnswer'].values())}")

    while not stop.is_set() and time.monotonic() < end:
        stop.wait(arrivals.expovariate(bursts.current()))
        if stop.is_set():
            break
        with pool_lock:
            pool[:] = [t for t in pool if t.is_alive()]
            busy = len(pool)
        if busy < args.workers:
            thread = threading.Thread(target=load.one, daemon=True)
            thread.start()
            with pool_lock:
                pool.append(thread)
        now = time.monotonic()
        if now >= next_session_check:
            next_session_check = now + 900
            try:
                load.keep_signed_in(load.customers + load.agents, people_password)
            except Exception as e:  # noqa: BLE001
                log(f"Signing in again failed: {e}")
        if now >= next_line:
            next_line += args.interval
            write_line()

    log("Stopping: waiting for running requests")
    for thread in list(pool):
        thread.join(timeout=130)
    write_line()
    failed = recorder.server_errors + recorder.no_answer
    log(f"Done. 5xx: {recorder.server_errors}, no answer: {recorder.no_answer}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
