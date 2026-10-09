-- Fails inside its transaction (S0-AC-08): the table is rolled back with it.
CREATE TABLE test_rolled_back (id bigint PRIMARY KEY, tenant_id bigint NOT NULL);
SELECT 1 / 0;
