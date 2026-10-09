-- Negative fixture for S0-AC-103: a business table without tenant_id.
CREATE TABLE widget (
    id bigint PRIMARY KEY,
    name text NOT NULL
);
