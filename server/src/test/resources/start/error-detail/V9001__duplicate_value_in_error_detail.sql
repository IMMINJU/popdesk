-- Fails with a unique violation whose DETAIL quotes the duplicated value:
-- "Key (code)=(v14-row-value-in-detail) is duplicated." (verify.md V-14).
CREATE TABLE test_error_detail (id bigint PRIMARY KEY, tenant_id bigint NOT NULL, code text NOT NULL);
INSERT INTO test_error_detail (id, tenant_id, code) VALUES (1, 1, 'v14-row-value-in-detail'), (2, 1, 'v14-row-value-in-detail');
CREATE UNIQUE INDEX test_error_detail_code ON test_error_detail (code);
