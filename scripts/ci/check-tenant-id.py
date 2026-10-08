#!/usr/bin/env python3
"""Check that every business table in the Flyway migrations has `tenant_id bigint NOT NULL` (ADR D-18).

Usage: check-tenant-id.py [MIGRATION_DIR]
Default directory: server/src/main/resources/db/migration
Passes when the directory is missing or holds no .sql files.

Also fails on ALTER TABLE statements that drop the column or its NOT NULL.
"""

import re
import sys
from pathlib import Path

DEFAULT_DIR = Path("server/src/main/resources/db/migration")

# Tables that are not business data. Add a table here only with a reason.
EXEMPT_TABLES = {
    "flyway_schema_history",      # Flyway's own history
    "spring_session",             # Spring Session JDBC (architecture.md section 5)
    "spring_session_attributes",  # Spring Session JDBC
    "tenant",                     # The team table itself (D-18): it is what tenant_id points to
}

CREATE_RE = re.compile(
    r"\bCREATE\s+(?:(?:GLOBAL|LOCAL)\s+)?(?:(?:TEMP|TEMPORARY|UNLOGGED)\s+)?TABLE\s+"
    r"(?:IF\s+NOT\s+EXISTS\s+)?([\w.\"]+)",
    re.IGNORECASE,
)
TENANT_COL_RE = re.compile(
    r"^\"?tenant_id\"?\s+(?:bigint|int8)\b(?=.*\bNOT\s+NULL\b)", re.IGNORECASE | re.DOTALL
)
ALTER_RE = re.compile(r"\bALTER\s+TABLE\b[^;]*", re.IGNORECASE)
BAD_ALTER_RE = re.compile(
    r"\bDROP\s+(?:COLUMN\s+)?(?:IF\s+EXISTS\s+)?\"?tenant_id\"?\b"
    r"|\bALTER\s+(?:COLUMN\s+)?\"?tenant_id\"?\s+(?:DROP\s+NOT\s+NULL|(?:SET\s+DATA\s+)?TYPE\b)",
    re.IGNORECASE,
)


def strip_comments(sql):
    sql = re.sub(r"/\*.*?\*/", " ", sql, flags=re.DOTALL)
    return re.sub(r"--[^\n]*", " ", sql)


def table_name(raw):
    return raw.split(".")[-1].strip('"').lower()


def body_after(sql, start):
    """Return the text inside the parentheses that open at or after `start`, or None."""
    i = start
    while i < len(sql) and sql[i] not in "(;":
        i += 1
    if i >= len(sql) or sql[i] != "(":
        return None
    depth = 0
    for j in range(i, len(sql)):
        if sql[j] == "(":
            depth += 1
        elif sql[j] == ")":
            depth -= 1
            if depth == 0:
                return sql[i + 1 : j]
    return None


def split_top_level(body):
    parts, depth, cur = [], 0, []
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur).strip())
    return parts


def check_file(path):
    errors = []
    sql = strip_comments(path.read_text(encoding="utf-8"))
    for m in CREATE_RE.finditer(sql):
        name = table_name(m.group(1))
        if name in EXEMPT_TABLES:
            continue
        body = body_after(sql, m.end())
        if body is None:
            errors.append(f"{path}: table {name}: no column list (CREATE TABLE AS/PARTITION OF is not checked), add tenant_id explicitly or exempt it")
            continue
        if not any(TENANT_COL_RE.match(col) for col in split_top_level(body)):
            errors.append(f"{path}: table {name}: missing `tenant_id bigint NOT NULL`")
    for m in ALTER_RE.finditer(sql):
        if BAD_ALTER_RE.search(m.group(0)):
            errors.append(f"{path}: ALTER TABLE drops or weakens tenant_id: {' '.join(m.group(0).split())}")
    return errors


def main():
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DIR
    files = sorted(root.glob("*.sql")) if root.is_dir() else []
    if not files:
        print(f"check-tenant-id: no migrations in {root}, nothing to check")
        return 0
    errors = [e for f in files for e in check_file(f)]
    for e in errors:
        print(f"error: {e}", file=sys.stderr)
    if errors:
        print(f"check-tenant-id: {len(errors)} problem(s) in {len(files)} file(s). See ADR D-18.", file=sys.stderr)
        return 1
    print(f"check-tenant-id: {len(files)} file(s) ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
