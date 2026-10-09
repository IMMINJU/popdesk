-- With Flyway's default transactional lock this waits for the lock's transaction, which waits for
-- this migration, and never ends (S0-AC-08, S0 premortem 3).
CREATE INDEX CONCURRENTLY test_indexed_code ON test_indexed (code);
