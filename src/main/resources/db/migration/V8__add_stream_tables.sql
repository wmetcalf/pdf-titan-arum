-- Opt-in borderless ("stream") table extraction, the HTTP-server counterpart of the CLI's
-- --stream-tables and the blastbox job field stream_tables. Mirrors V7__add_skip_tables.sql.
--
-- DEFAULT FALSE is deliberate and load-bearing: it matches the flag's shipping default (the
-- stream path is opt-in and OFF). Because the column is added with a default, PostgreSQL
-- backfills every pre-existing row with FALSE, so jobs submitted before this migration -- and
-- any INSERT that omits the column -- read back as "stream path off", i.e. exactly the
-- behaviour those rows were created under. Adding the column is therefore backward compatible
-- in both directions: old rows keep their old meaning, and old INSERT statements still work.
ALTER TABLE jobs
    ADD COLUMN stream_tables BOOLEAN NOT NULL DEFAULT FALSE;
