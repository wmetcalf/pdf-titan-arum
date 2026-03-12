-- Per-job PDF password for encrypted PDFs; cleared from DB as soon as the worker reads it
ALTER TABLE jobs ADD COLUMN password TEXT;
