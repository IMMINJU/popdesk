-- A migration that breaks the sample data on purpose (S0 premortem section 8). The upgrade
-- harness must fail when it runs, and each of its four checks must fail: if one passes, it would
-- not catch a real broken migration either (S0-AC-104).
--
-- It names no table. It finds tables by their names in information_schema and changes one thing
-- for each check:
--
--   attachments  every bytea value in popdesk's schema (outside Spring Session's tables) gets one
--                more zero byte, so attachment contents (POPDESK_STORAGE=db) no longer match the
--                hashes the sample generator recorded.
--   issues       in the issue table (a name that is "issue" or "issues", or ends in "_issue" or
--                "_issues"), every integer column outside its primary key and foreign keys whose name
--                ends in "number", "num", "no", "seq", or "sequence" (the issue number) goes up by
--                1,000,000, so every issue key changes; and every text value shaped like an issue
--                key ({company code}-{number}) gets a "9" at the end.
--   outbox       one row is deleted from each table whose name holds "outbox" or "queue".
--   audit        one row is deleted from each table whose name holds "audit" and that has more than
--                one row. A table with a single row keeps it: slice 1b keeps the head of the hash
--                chain in such a table, and without it nobody can sign in, so the other checks
--                would fail for that reason and not for their own.
--
-- A part that finds no table to change raises an error, so the app does not finish migrating and
-- the harness exits with 2: the test's assumption about table names is then wrong, not the harness.
-- Accounts, companies, and sessions stay as they are, so the app still starts and the harness
-- reaches its data checks.
DO $$
DECLARE
    c record;
    t record;
    issue_tables int := 0;
    issue_changes int := 0;
    outbox_tables int := 0;
    audit_tables int := 0;
    changed bigint;
BEGIN
    FOR c IN
        SELECT col.table_name, col.column_name
        FROM information_schema.columns col
        JOIN information_schema.tables tab
            ON tab.table_schema = col.table_schema AND tab.table_name = col.table_name AND tab.table_type = 'BASE TABLE'
        WHERE col.table_schema = current_schema()
          AND col.data_type = 'bytea'
          AND col.table_name NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
    LOOP
        EXECUTE format('UPDATE %I SET %I = %I || ''\x00''::bytea WHERE %I IS NOT NULL',
                       c.table_name, c.column_name, c.column_name, c.column_name);
    END LOOP;

    FOR t IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
          AND table_name ~ '(^|_)issues?$'
    LOOP
        issue_tables := issue_tables + 1;
        FOR c IN
            SELECT col.column_name, col.data_type
            FROM information_schema.columns col
            WHERE col.table_schema = current_schema() AND col.table_name = t.table_name
              AND col.data_type IN ('integer', 'bigint', 'smallint', 'text', 'character varying')
              AND NOT EXISTS (
                  SELECT 1 FROM information_schema.key_column_usage k
                  JOIN information_schema.table_constraints tc
                      ON tc.constraint_schema = k.constraint_schema AND tc.constraint_name = k.constraint_name
                  WHERE k.table_schema = col.table_schema AND k.table_name = col.table_name
                    AND k.column_name = col.column_name
                    AND tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY'))
        LOOP
            IF c.data_type IN ('integer', 'bigint', 'smallint') AND c.column_name ~ '(number|num|no|seq|sequence)$' THEN
                EXECUTE format('UPDATE %I SET %I = %I + 1000000 WHERE %I IS NOT NULL',
                               t.table_name, c.column_name, c.column_name, c.column_name);
                issue_changes := issue_changes + 1;
            ELSIF c.data_type IN ('text', 'character varying') THEN
                EXECUTE format('UPDATE %I SET %I = %I || ''9'' WHERE %I ~ ''^[A-Z0-9]+-[0-9]+$''',
                               t.table_name, c.column_name, c.column_name, c.column_name);
                GET DIAGNOSTICS changed = ROW_COUNT;
                IF changed > 0 THEN
                    issue_changes := issue_changes + 1;
                END IF;
            END IF;
        END LOOP;
    END LOOP;
    IF issue_tables = 0 OR issue_changes = 0 THEN
        RAISE EXCEPTION 'V999: no issue table with an issue number column or a stored issue key';
    END IF;

    FOR t IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
          AND (table_name LIKE '%outbox%' OR table_name LIKE '%queue%')
    LOOP
        outbox_tables := outbox_tables + 1;
        EXECUTE format('DELETE FROM %I WHERE ctid = (SELECT ctid FROM %I LIMIT 1)', t.table_name, t.table_name);
    END LOOP;
    IF outbox_tables = 0 THEN
        RAISE EXCEPTION 'V999: no outbox or queue table';
    END IF;

    FOR t IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
          AND table_name LIKE '%audit%'
    LOOP
        EXECUTE format('DELETE FROM %I WHERE ctid = (SELECT ctid FROM %I LIMIT 1) AND (SELECT count(*) FROM %I) > 1',
                       t.table_name, t.table_name, t.table_name);
        GET DIAGNOSTICS changed = ROW_COUNT;
        IF changed > 0 THEN
            audit_tables := audit_tables + 1;
        END IF;
    END LOOP;
    IF audit_tables = 0 THEN
        RAISE EXCEPTION 'V999: no audit table';
    END IF;
END $$;
