-- CREATE INDEX CONCURRENTLY runs outside a transaction; it fails on the duplicates and leaves a
-- failed row in the migration history (S0-AC-08).
CREATE UNIQUE INDEX CONCURRENTLY test_duplicates_code ON test_duplicates (code);
