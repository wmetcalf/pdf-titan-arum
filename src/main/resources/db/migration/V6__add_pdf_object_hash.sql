-- Promote PDF structural fingerprint to a top-level column alongside file_hash
ALTER TABLE jobs ADD COLUMN pdf_object_hash TEXT;
CREATE INDEX jobs_pdf_object_hash ON jobs (pdf_object_hash) WHERE pdf_object_hash IS NOT NULL;
