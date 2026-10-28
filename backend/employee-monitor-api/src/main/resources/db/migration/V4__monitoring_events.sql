-- =====================================================================
-- V4: Monitoring categories (Milestone 4).
-- Application usage, website (domain-level) usage, USB events, network
-- events and Zscaler status. All ingest is idempotent per (device, client
-- event id). No URLs/page contents, no file contents, no packet capture.
-- =====================================================================

CREATE TABLE application_usage (
    id               BIGSERIAL PRIMARY KEY,
    client_event_id  UUID        NOT NULL,
    device_id        BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id      BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    application_name VARCHAR(255) NOT NULL,
    process_name     VARCHAR(255),
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ NOT NULL,
    duration_seconds INTEGER     NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_usage_client UNIQUE (device_id, client_event_id),
    CONSTRAINT chk_app_duration CHECK (duration_seconds >= 0)
);
CREATE INDEX idx_app_usage_employee_time ON application_usage (employee_id, started_at);
CREATE INDEX idx_app_usage_device_time ON application_usage (device_id, started_at);

CREATE TABLE website_usage (
    id               BIGSERIAL PRIMARY KEY,
    client_event_id  UUID        NOT NULL,
    device_id        BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id      BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    domain           VARCHAR(255) NOT NULL,
    browser          VARCHAR(120),
    started_at       TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ NOT NULL,
    duration_seconds INTEGER     NOT NULL,
    received_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_web_usage_client UNIQUE (device_id, client_event_id),
    CONSTRAINT chk_web_duration CHECK (duration_seconds >= 0)
);
CREATE INDEX idx_web_usage_employee_time ON website_usage (employee_id, started_at);
CREATE INDEX idx_web_usage_device_time ON website_usage (device_id, started_at);

CREATE TABLE usb_events (
    id              BIGSERIAL PRIMARY KEY,
    client_event_id UUID        NOT NULL,
    device_id       BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    event_type      VARCHAR(20) NOT NULL,          -- CONNECTED | REMOVED
    device_class    VARCHAR(80),                   -- e.g. USB Mass Storage
    vendor_id       VARCHAR(16),
    product_id      VARCHAR(16),
    serial_number   VARCHAR(128),
    description     VARCHAR(255),
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_usb_client UNIQUE (device_id, client_event_id),
    CONSTRAINT chk_usb_event_type CHECK (event_type IN ('CONNECTED', 'REMOVED'))
);
CREATE INDEX idx_usb_employee_time ON usb_events (employee_id, occurred_at);
CREATE INDEX idx_usb_device_time ON usb_events (device_id, occurred_at);

CREATE TABLE network_events (
    id              BIGSERIAL PRIMARY KEY,
    client_event_id UUID        NOT NULL,
    device_id       BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    event_type      VARCHAR(30) NOT NULL,          -- CONNECTED | DISCONNECTED | CHANGED
    connection_type VARCHAR(20),                   -- WIFI | ETHERNET | VPN | OTHER
    network_name    VARCHAR(255),
    ip_address      VARCHAR(64),
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_network_client UNIQUE (device_id, client_event_id)
);
CREATE INDEX idx_network_employee_time ON network_events (employee_id, occurred_at);
CREATE INDEX idx_network_device_time ON network_events (device_id, occurred_at);

CREATE TABLE zscaler_events (
    id              BIGSERIAL PRIMARY KEY,
    client_event_id UUID        NOT NULL,
    device_id       BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    status          VARCHAR(20) NOT NULL,          -- CONNECTED | DISCONNECTED | ERROR | UNKNOWN
    detail          VARCHAR(255),
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_zscaler_client UNIQUE (device_id, client_event_id),
    CONSTRAINT chk_zscaler_status CHECK (status IN ('CONNECTED', 'DISCONNECTED', 'ERROR', 'UNKNOWN'))
);
CREATE INDEX idx_zscaler_employee_time ON zscaler_events (employee_id, occurred_at);
CREATE INDEX idx_zscaler_device_time ON zscaler_events (device_id, occurred_at);
