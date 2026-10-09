-- Negative fixture for S0-AC-103: tenant_id made, then made nullable.
CREATE TABLE widget (
    id bigint PRIMARY KEY,
    tenant_id bigint NOT NULL,
    name text NOT NULL
);
ALTER TABLE widget ALTER COLUMN tenant_id DROP NOT NULL;
