#!/usr/bin/env python3
"""Check the hash chain of a popdesk audit log export.

Usage: audit-verify.py EXPORT.jsonl      (or - for standard input)

EXPORT.jsonl is a JSON Lines export of the audit log made without a filter. The rules are in
conventions.md, "Audit log hash chain". This script uses the Python standard library only and no
code of popdesk, so it also shows that the rules as written are enough.

Checked for every line: `hash` is SHA-256 of `prevHash` and the entry's canonical JSON, `prevHash` is
the `hash` of the line before, and `seq` goes up by one. A file that starts at `seq` 1 must start
from 64 zeros. A file that starts later (older entries were removed by the retention period) is
checked from its first line on; what came before cannot be checked from the file.

It cannot tell whether entries are missing at the end of the file, or whether the whole chain was
written again: compare the last `hash` it prints with one you kept somewhere else.

Exit code 0: the chain holds. 1: it does not, or the file cannot be read. 2: wrong usage.
"""

import hashlib
import io
import json
import re
import sys
from datetime import datetime, timedelta, timezone

ZERO_HASH = "0" * 64
FIELDS = {"id", "seq", "prevHash", "hash", "action", "actor", "target", "changes", "occurredAt"}
TIME_RE = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$")
ESCAPES = {'"': '\\"', "\\": "\\\\", "\b": "\\b", "\f": "\\f", "\n": "\\n", "\r": "\\r", "\t": "\\t"}


class Broken(Exception):
    pass


def no_fraction(text):
    raise Broken(f"a number with a fraction ({text}); audit values hold whole numbers only")


def canonical(value):
    """RFC 8785 text of a JSON value that holds no fractions."""
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return '"' + "".join(ESCAPES.get(c) or (f"\\u{ord(c):04x}" if ord(c) < 0x20 else c) for c in value) + '"'
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        # Keys in the order of their UTF-16 code units.
        keys = sorted(value, key=lambda key: key.encode("utf-16-be"))
        return "{" + ",".join(canonical(key) + ":" + canonical(value[key]) for key in keys) + "}"
    raise Broken(f"a value of type {type(value).__name__}")


def time_text(written):
    """The time in UTC with exactly six fraction digits; finer digits are cut off."""
    match = TIME_RE.match(written) if isinstance(written, str) else None
    if not match:
        raise Broken(f"occurredAt is not a time: {written!r}")
    year, month, day, hour, minute, second, fraction, zone = match.groups()
    micros = int(((fraction or "") + "000000")[:6])
    offset = timedelta(0)
    if zone != "Z":
        offset = timedelta(hours=int(zone[1:3]), minutes=int(zone[4:6])) * (1 if zone[0] == "+" else -1)
    at = datetime(int(year), int(month), int(day), int(hour), int(minute), int(second), micros,
                  tzinfo=timezone(offset)).astimezone(timezone.utc)
    return at.strftime("%Y-%m-%dT%H:%M:%S.") + f"{at.microsecond:06d}Z"


def entry_hash(entry):
    actor = entry["actor"]
    if not isinstance(actor, dict) or set(actor) != {"accountId", "name", "email"}:
        raise Broken("actor is not {accountId, name, email}")
    text = canonical({
        "action": entry["action"],
        "actor": actor,
        "target": entry["target"],
        "changes": entry["changes"],
        "occurredAt": time_text(entry["occurredAt"]),
    })
    return hashlib.sha256((entry["prevHash"] + text).encode("utf-8")).hexdigest()


def verify(lines):
    before = None
    count = 0
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            entry = json.loads(line, parse_float=no_fraction)
            if not isinstance(entry, dict) or set(entry) != FIELDS:
                raise Broken(f"the fields are not {sorted(FIELDS)}")
            if before is None:
                if entry["seq"] == 1 and entry["prevHash"] != ZERO_HASH:
                    raise Broken("the first entry of the chain does not start from 64 zeros")
                if entry["seq"] != 1:
                    print(f"The file starts at seq {entry['seq']}: entries before it are not in the file.")
            elif entry["seq"] != before["seq"] + 1:
                raise Broken(f"seq {entry['seq']} follows seq {before['seq']}")
            elif entry["prevHash"] != before["hash"]:
                raise Broken("prevHash is not the hash of the line before")
            if entry["hash"] != entry_hash(entry):
                raise Broken("hash is not SHA-256 of prevHash and the canonical JSON")
        except (Broken, ValueError, KeyError, TypeError) as e:
            print(f"BROKEN at line {number}: {e}")
            return False
        before = entry
        count += 1
    if before is None:
        print("BROKEN: the file has no entries")
        return False
    print(f"OK: {count} entries, last seq {before['seq']}, last hash {before['hash']}")
    return True


def main():
    if len(sys.argv) != 2:
        print(__doc__.strip().splitlines()[2].strip(), file=sys.stderr)
        return 2
    if sys.argv[1] == "-":
        return 0 if verify(io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8")) else 1
    with open(sys.argv[1], encoding="utf-8") as lines:
        return 0 if verify(lines) else 1


if __name__ == "__main__":
    sys.exit(main())
