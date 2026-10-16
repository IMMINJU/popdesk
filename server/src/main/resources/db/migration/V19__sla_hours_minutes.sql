-- Slice 8a: the business hours of a weekday as minutes of the day, 0 to 1440 (1440 is 24:00, the end of the day).
-- V18 made them time columns; business tables keep no time without a time zone (S0-AC-12), and the calculation
-- counts in minutes of the day already. Both columns change in one statement so closes > opens is checked on the
-- new type.
ALTER TABLE sla_hours
    ALTER COLUMN opens TYPE smallint USING (extract(hour FROM opens) * 60 + extract(minute FROM opens))::smallint,
    ALTER COLUMN closes TYPE smallint USING (extract(hour FROM closes) * 60 + extract(minute FROM closes))::smallint,
    ADD CONSTRAINT sla_hours_minutes CHECK (opens >= 0 AND closes <= 1440);
