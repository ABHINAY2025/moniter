-- =====================================================================
-- V5: Screenshots (Milestone 5).
-- Only metadata lives in the database; the (optionally encrypted) image
-- bytes live in object storage keyed by storage_key. Images are never
-- served publicly - retrieval is authenticated, authorized and audited.
-- =====================================================================

CREATE TABLE screenshots (
    id              BIGSERIAL PRIMARY KEY,
    client_event_id UUID        NOT NULL,
    device_id       BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    captured_at     TIMESTAMPTZ NOT NULL,
    storage_key     VARCHAR(255) NOT NULL UNIQUE,
    content_type    VARCHAR(40)  NOT NULL,
    width           INTEGER,
    height          INTEGER,
    size_bytes      INTEGER     NOT NULL,
    encrypted       BOOLEAN     NOT NULL DEFAULT FALSE,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_screenshot_client UNIQUE (device_id, client_event_id),
    CONSTRAINT chk_screenshot_size CHECK (size_bytes >= 0)
);

CREATE INDEX idx_screenshots_employee_time ON screenshots (employee_id, captured_at DESC);
CREATE INDEX idx_screenshots_device_time ON screenshots (device_id, captured_at DESC);
