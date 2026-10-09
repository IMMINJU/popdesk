-- A table with duplicate values, so the unique index of the next migration cannot be built.
CREATE TABLE test_duplicates (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, code text NOT NULL);
INSERT INTO test_duplicates (id, tenant_id, code) VALUES (1, 1, 'same'), (2, 1, 'same');
