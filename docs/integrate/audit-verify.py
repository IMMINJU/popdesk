#!/usr/bin/env python3
"""Check the hash chain of a popdesk audit log export.

Usage: audit-verify.py EXPORT.jsonl      (or - for standard input)

EXPORT.jsonl is a JSON Lines export of the audit log made without a filter. The rules are in
conventions.md, "Audit log hash chain". This script uses the Python standard library only and no
code of popdesk, so it also shows that the rules as written are enough.

Checked for every entry, in the order of `seq`: `hash` is SHA-256 of `prevHash` and the entry's
canonical JSON, `prevHash` is the `hash` of the entry before, and `seq` goes up by one. A file that
starts at `seq` 1 must start from 64 zeros. A file that starts later (older entries were removed by
the retention period) is checked from its first entry on; what came before cannot be checked from
the file.

The same file can hold outside copies received before and after an anonymization computed the chain
again (conventions.md, "Anonymization and the chain"). Each `AUDIT_RECHAINED` entry (seq R, range F
to T in `fromSeq` and `toSeq`) is a boundary: a `prevHash` that is not the `hash` of the entry before
is not a break when the entry is in F to T or is R itself, and the range is printed as
"rechained F-T at R". R's `prevHash` must still be its `headHash.to`, unless R lies in the range of a
later `AUDIT_RECHAINED`. The same `seq` twice counts once when both hashes are the same; with another
hash, the later line is taken only when the `seq` lies in the range of an `AUDIT_RECHAINED` after it.

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


def rechained_range(entry):
    """(R, F, T, headHash.to) of an AUDIT_RECHAINED entry, or None for any other entry."""
    if entry["action"] != "AUDIT_RECHAINED":
        return None
    changes = entry["changes"]
    if not isinstance(changes, dict):
        raise Broken("AUDIT_RECHAINED has no changes")
    first, last, head = changes.get("fromSeq"), changes.get("toSeq"), changes.get("headHash")
    if (not isinstance(first, int) or not isinstance(last, int) or not isinstance(head, dict)
            or not isinstance(head.get("to"), str) or not first <= last < entry["seq"]):
        raise Broken("AUDIT_RECHAINED does not hold fromSeq <= toSeq < its seq and headHash.to")
    return entry["seq"], first, last, head["to"]


def read(lines):
    """The entries by seq, each with the number of its line, and the ranges of the AUDIT_RECHAINED ones."""
    found = []
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            entry = json.loads(line, parse_float=no_fraction)
            if not isinstance(entry, dict) or set(entry) != FIELDS:
                raise Broken(f"the fields are not {sorted(FIELDS)}")
            if not isinstance(entry["seq"], int):
                raise Broken("seq is not a whole number")
            found.append((number, entry, rechained_range(entry)))
        except (Broken, ValueError, KeyError, TypeError) as e:
            raise Broken(f"at line {number}: {e}") from None
    ranges = {r[0]: r for _, _, r in found if r is not None}
    by_seq = {}
    for number, entry, _ in found:
        seq = entry["seq"]
        if seq not in by_seq or by_seq[seq][1]["hash"] == entry["hash"]:
            by_seq.setdefault(seq, (number, entry))
        elif in_later_range(seq, ranges):
            by_seq[seq] = (number, entry)
        else:
            raise Broken(f"at line {number}: seq {seq} came before with another hash, and no AUDIT_RECHAINED "
                         "after it computed it again")
    return [by_seq[seq] for seq in sorted(by_seq)], ranges


def in_later_range(seq, ranges):
    """Whether an AUDIT_RECHAINED after seq computed it again."""
    return any(at > seq and first <= seq <= last for at, first, last, _ in ranges.values())


def verify(lines):
    try:
        entries, ranges = read(lines)
    except Broken as e:
        print(f"BROKEN {e}")
        return False
    before = None
    for number, entry in entries:
        try:
            seq = entry["seq"]
            if before is None:
                if seq == 1 and entry["prevHash"] != ZERO_HASH:
                    raise Broken("the first entry of the chain does not start from 64 zeros")
                if seq != 1:
                    print(f"The file starts at seq {seq}: entries before it are not in the file.")
            elif seq != before["seq"] + 1:
                raise Broken(f"seq {seq} follows seq {before['seq']}")
            elif entry["prevHash"] != before["hash"] and seq not in ranges and not any(
                    first <= seq <= last for _, first, last, _ in ranges.values()):
                raise Broken("prevHash is not the hash of the line before")
            if seq in ranges:
                at, first, last, head = ranges[seq]
                if entry["prevHash"] != head and not in_later_range(seq, ranges):
                    raise Broken("the prevHash of AUDIT_RECHAINED is not its headHash.to")
            if entry["hash"] != entry_hash(entry):
                raise Broken("hash is not SHA-256 of prevHash and the canonical JSON")
        except (Broken, ValueError, KeyError, TypeError) as e:
            print(f"BROKEN at line {number}: {e}")
            return False
        if seq in ranges:
            print(f"rechained {ranges[seq][1]}-{ranges[seq][2]} at {seq}")
        before = entry
    if before is None:
        print("BROKEN: the file has no entries")
        return False
    print(f"OK: {len(entries)} entries, last seq {before['seq']}, last hash {before['hash']}")
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
