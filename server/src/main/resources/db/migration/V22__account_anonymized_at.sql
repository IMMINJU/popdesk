-- Account export and anonymization (PII-3, PII-4). Slice 10a design section 1.

-- When the account was anonymized (PII-4); null for every other account, so every account an older
-- version made is not anonymized. An anonymized account cannot be brought back: the requests that
-- would make it usable again check this column first (S10A-AC-13).
ALTER TABLE account ADD COLUMN anonymized_at timestamptz;
