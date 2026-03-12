CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename        TEXT NOT NULL,
    file_hash       TEXT,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    status          TEXT NOT NULL DEFAULT 'pending',
    worker_host     TEXT,
    error_text      TEXT,
    report          JSONB,
    artifact_root   TEXT
);

CREATE INDEX jobs_status_submitted ON jobs (status, submitted_at);
