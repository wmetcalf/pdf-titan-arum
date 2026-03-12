-- Per-job processing options; NULL means "use server default"
ALTER TABLE jobs
    ADD COLUMN dpi               REAL,
    ADD COLUMN pages_spec        TEXT,
    ADD COLUMN add_link_annotations BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN no_skip_blanks    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN ocr_lang          TEXT,
    ADD COLUMN timeout_seconds   INTEGER;
