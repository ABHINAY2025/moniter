-- =====================================================================
-- V6: Live view (Milestone 6).
-- Authorized, time-limited, fully audited live screen-view sessions.
-- The backend is the signaling/authorization authority; media (WebRTC or
-- the frame-relay fallback) flows per session between the authorized admin
-- and the target device only.
-- =====================================================================

INSERT INTO permissions (name, description) VALUES
    ('LIVE_VIEW', 'Start an authorized live screen-view session');

-- SUPER_ADMIN already has every permission via the V2 cross join is NOT retroactive,
-- so grant the new permission explicitly to the roles that may use it.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'LIVE_VIEW'
WHERE r.name IN ('SUPER_ADMIN', 'MANAGER');

CREATE TABLE live_view_sessions (
    id              BIGSERIAL PRIMARY KEY,
    session_id      UUID        NOT NULL UNIQUE,
    admin_user_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    admin_username  VARCHAR(80),
    device_id       BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id     BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    connected_at    TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    duration_seconds INTEGER,
    end_reason      VARCHAR(40),
    created_ip      VARCHAR(64),
    CONSTRAINT chk_live_view_status CHECK (status IN ('PENDING', 'ACTIVE', 'ENDED'))
);

CREATE INDEX idx_live_view_device ON live_view_sessions (device_id, started_at DESC);
CREATE INDEX idx_live_view_employee ON live_view_sessions (employee_id, started_at DESC);
CREATE INDEX idx_live_view_admin ON live_view_sessions (admin_user_id, started_at DESC);
-- At most one live (non-ended) session per device.
CREATE UNIQUE INDEX uq_live_view_active_device ON live_view_sessions (device_id) WHERE status <> 'ENDED';
