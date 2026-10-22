-- The index of a search text of one or two characters (B8-AC-02, B8-AC-06), which the trigram index of
-- V11 cannot serve. Slice b8 acceptance section 1, 100,000 issues of about 2,300 characters: a two-character
-- text that 10 issues hold went from 4.9 seconds to 0.4 milliseconds, one that 20,000 hold from 5.4 seconds
-- to 14 milliseconds; the index is about 395 MB on top of the trigram one.

-- Every character of the lowercased text and every two neighboring ones, once each. string_to_array and
-- lead() take 3.8 ms a row where substr over generate_series took 200 ms. The body is bound when it is
-- created and names only pg_catalog, so it works with an empty search_path, as pg_restore runs (B8-AC-07).
CREATE FUNCTION issue_search_grams(t text) RETURNS text[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
BEGIN ATOMIC
    SELECT COALESCE(pg_catalog.array_agg(DISTINCT v.g COLLATE "C"), '{}')
    FROM (SELECT u.c, pg_catalog.lead(u.c) OVER (ORDER BY u.n) AS next
          FROM pg_catalog.unnest(pg_catalog.string_to_array(pg_catalog.lower(t), NULL)) WITH ORDINALITY AS u(c, n)) s,
        LATERAL (VALUES (s.c), (s.c OPERATOR(pg_catalog.||) s.next)) AS v(g)
    WHERE v.g IS NOT NULL;
END;

-- In the database's own collation GIN sorts the about 2,900 grams of an issue with libc, 56 ms a row;
-- with "C" 500 issues took 2.5 seconds instead of 27.6, about 8 minutes for 100,000.
CREATE INDEX issue_search_grams_idx ON issue USING gin ((issue_search_grams(search_text)) COLLATE "C");
