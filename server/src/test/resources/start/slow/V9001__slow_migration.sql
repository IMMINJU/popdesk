-- A migration that takes a few seconds, so a test can look at the app while migrations run
-- (S0-AC-01 order, S0-AC-09 closed ports). Versions 9000 and up are test fixtures.
SELECT pg_sleep(8);
