-- =====================================================================
-- V3: Session & activity events reported by the agent (Milestone 2).
-- One append-only row per transition; daily durations are derived from
-- the ordered stream rather than stored, so late/duplicate events are safe.
-- =====================================================================

CREATE TABLE session_events (
    id              BIGSERIAL PRIMARY KEY,
    -- Agent-generated id; makes delivery idempotent if a batch is retried.
    client_event_id UUID        NOT NULL,
    device_id       BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    event_type      VARCHAR(30) NOT NULL,
    category        VARCHAR(20) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_session_events_client UNIQUE (device_id, client_event_id),
    CONSTRAINT chk_session_event_category CHECK (category IN ('SESSION', 'ACTIVITY', 'POWER'))
);

CREATE INDEX idx_session_events_device_time ON session_events (device_id, occurred_at);
CREATE INDEX idx_session_events_employee_time ON session_events (employee_id, occurred_at);
CREATE INDEX idx_session_events_type_time ON session_events (event_type, occurred_at);
