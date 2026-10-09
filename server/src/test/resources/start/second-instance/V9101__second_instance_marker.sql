-- Run only by an app that got as far as Flyway. A second instance must stop before that (S0-AC-11).
CREATE TABLE test_second_instance_ran (id bigint PRIMARY KEY, tenant_id bigint NOT NULL);
