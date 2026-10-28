-- =====================================================================
-- V8: Suspicious-activity alerts.
-- Server-side rules raise alerts (USB inserted, flagged website domain,
-- device offline during work hours). Alerts are pushed live to admins over
-- the existing em:events channel and listed/acknowledged via /api/alerts.
-- =====================================================================

INSERT INTO permissions (name, description) VALUES
    ('ALERT_READ', 'View and acknowledge suspicious-activity alerts');

-- The V2 cross join that gives SUPER_ADMIN every permission is not retroactive,
-- so grant the new permission explicitly to the all-seeing admin roles.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'ALERT_READ'
WHERE r.name IN ('SUPER_ADMIN', 'IT_ADMIN', 'HR_ADMIN');

CREATE TABLE alerts (
    id              BIGSERIAL PRIMARY KEY,
    category        VARCHAR(40)  NOT NULL,   -- USB_CONNECTED | FLAGGED_WEBSITE | OFFLINE_DURING_WORK
    severity        VARCHAR(20)  NOT NULL DEFAULT 'WARNING',
    message         VARCHAR(500) NOT NULL,
    device_id       BIGINT REFERENCES devices (id) ON DELETE SET NULL,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    device_code     VARCHAR(40),
    employee_name   VARCHAR(160),
    dedup_key       VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by BIGINT REFERENCES users (id) ON DELETE SET NULL
);

CREATE INDEX idx_alerts_created ON alerts (created_at DESC);
CREATE INDEX idx_alerts_unack ON alerts (created_at DESC) WHERE acknowledged_at IS NULL;
CREATE INDEX idx_alerts_dedup ON alerts (device_id, category, dedup_key, created_at DESC);
