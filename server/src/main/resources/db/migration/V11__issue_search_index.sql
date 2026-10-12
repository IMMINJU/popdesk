-- The index of the issue search (S3-AC-17), built after V10 filled search_text: filling 100,000
-- issues under the index took 8 minutes, building it afterwards 70 seconds (slice 3 design, section 1).
CREATE INDEX issue_search_text_trgm_idx ON issue USING gin (search_text gin_trgm_ops);
