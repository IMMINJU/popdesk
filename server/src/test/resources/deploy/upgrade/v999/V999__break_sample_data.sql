-- A migration that breaks the sample data on purpose (S0 premortem section 8). The upgrade
-- harness must fail when it runs: if it passes, its checks would not catch a real broken migration.
--
-- It appends one zero byte to every bytea value in popdesk's schema, outside Spring Session's
-- tables, without naming any table: attachment contents (POPDESK_STORAGE=db) no longer match the
-- hashes the sample generator recorded. Accounts and sessions stay as they are, so the app still
-- starts and the harness reaches its data checks.
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN
        SELECT col.table_name, col.column_name
        FROM information_schema.columns col
        JOIN information_schema.tables t
            ON t.table_schema = col.table_schema AND t.table_name = col.table_name AND t.table_type = 'BASE TABLE'
        WHERE col.table_schema = current_schema()
          AND col.data_type = 'bytea'
          AND col.table_name NOT IN ('flyway_schema_history', 'spring_session', 'spring_session_attributes')
    LOOP
        EXECUTE format('UPDATE %I SET %I = %I || ''\x00''::bytea WHERE %I IS NOT NULL',
                       c.table_name, c.column_name, c.column_name, c.column_name);
    END LOOP;
END $$;
