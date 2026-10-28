-- Optional blob storage in PostgreSQL for screenshots and signed agent packages, so a deployment can keep
-- everything in the database (one pg_dump backs it up, no filesystem volume to manage).
-- Enabled with app.screenshots.storage=database and/or app.updates.storage=database; unused otherwise.

CREATE TABLE screenshot_blobs (
    storage_key VARCHAR(80)  PRIMARY KEY,
    data        BYTEA        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE update_blobs (
    storage_key VARCHAR(160) PRIMARY KEY,
    data        BYTEA        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
