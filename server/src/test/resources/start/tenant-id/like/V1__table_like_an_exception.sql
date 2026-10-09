-- Negative fixture for S0-AC-103: a table made with (LIKE x) from a table that has no tenant_id.
-- tenant is on the exception list; the copy is a business table and is not.
CREATE TABLE tenant (
    id bigint PRIMARY KEY,
    name text NOT NULL
);
CREATE TABLE tenant_copy (LIKE tenant INCLUDING ALL);
