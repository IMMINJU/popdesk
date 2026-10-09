-- A table for the next migration to index.
CREATE TABLE test_indexed (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, code text NOT NULL);
INSERT INTO test_indexed (id, tenant_id, code) VALUES (1, 1, 'a'), (2, 1, 'b');
