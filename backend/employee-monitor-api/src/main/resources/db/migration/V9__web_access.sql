-- =====================================================================
-- V9: Website restriction & access monitoring.
-- Web-access policies (ALLOW/BLOCK/MONITOR rules) assignable to devices or
-- employees, enforcement status per device, and domain-level access events.
-- Single-organization model: isolation is via RBAC + manager/employee scope
-- (there is no tenant table in this platform).
-- =====================================================================

INSERT INTO permissions (name, description) VALUES
    ('WEB_POLICY_READ', 'View web-access policies and device enforcement status'),
    ('WEB_POLICY_MANAGE', 'Create, edit, assign and delete web-access policies'),
    ('WEB_EVENT_READ', 'View website access events');

-- Read-ish permissions for the admin roles; manage only for SUPER_ADMIN/IT_ADMIN.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name IN ('WEB_POLICY_READ', 'WEB_EVENT_READ')
WHERE r.name IN ('SUPER_ADMIN', 'IT_ADMIN', 'HR_ADMIN', 'MANAGER');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'WEB_POLICY_MANAGE'
WHERE r.name IN ('SUPER_ADMIN', 'IT_ADMIN');

CREATE TABLE web_policies (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(500),
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    priority    INTEGER      NOT NULL DEFAULT 100,   -- higher wins in conflicts
    valid_from  TIMESTAMPTZ,
    valid_to    TIMESTAMPTZ,
    revision    INTEGER      NOT NULL DEFAULT 1,
    created_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE web_policy_rules (
    id        BIGSERIAL PRIMARY KEY,
    policy_id BIGINT      NOT NULL REFERENCES web_policies (id) ON DELETE CASCADE,
    domain    VARCHAR(255),                -- canonical domain (host-suffix match), or NULL for a category rule
    category  VARCHAR(40),                 -- optional website category
    action    VARCHAR(10) NOT NULL,        -- ALLOW | BLOCK | MONITOR
    CONSTRAINT chk_web_rule_action CHECK (action IN ('ALLOW', 'BLOCK', 'MONITOR')),
    CONSTRAINT chk_web_rule_target CHECK (domain IS NOT NULL OR category IS NOT NULL)
);
CREATE INDEX idx_web_rule_policy ON web_policy_rules (policy_id);

CREATE TABLE web_policy_assignments (
    id          BIGSERIAL PRIMARY KEY,
    policy_id   BIGINT      NOT NULL REFERENCES web_policies (id) ON DELETE CASCADE,
    target_type VARCHAR(10) NOT NULL,      -- DEVICE | EMPLOYEE
    target_id   BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_web_assign_type CHECK (target_type IN ('DEVICE', 'EMPLOYEE')),
    CONSTRAINT uq_web_assign UNIQUE (policy_id, target_type, target_id)
);
CREATE INDEX idx_web_assign_target ON web_policy_assignments (target_type, target_id);

CREATE TABLE web_enforcement_status (
    device_id        BIGINT PRIMARY KEY REFERENCES devices (id) ON DELETE CASCADE,
    mechanism        VARCHAR(40),
    healthy          BOOLEAN     NOT NULL DEFAULT FALSE,
    applied_revision VARCHAR(64),
    browsers         VARCHAR(160),
    last_sync_at     TIMESTAMPTZ,
    last_event_at    TIMESTAMPTZ,
    last_error       VARCHAR(255),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE web_access_events (
    id                 BIGSERIAL PRIMARY KEY,
    client_event_id    UUID,
    device_id          BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    employee_id        BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    domain             VARCHAR(255) NOT NULL,
    browser            VARCHAR(80),
    event_type         VARCHAR(24) NOT NULL,   -- see WebEventType
    enforcement_result VARCHAR(24),            -- ALLOWED | BLOCKED | MONITORED | ERROR | NONE
    matched_policy_id  BIGINT REFERENCES web_policies (id) ON DELETE SET NULL,
    matched_rule_id    BIGINT,
    source             VARCHAR(40),            -- DNS_LOG | CHROME_POLICY | ...
    occurred_at        TIMESTAMPTZ NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_web_event_type CHECK (event_type IN
        ('NAVIGATION_OBSERVED', 'ACCESS_ATTEMPT', 'ACCESS_ALLOWED', 'ACCESS_BLOCKED',
         'POLICY_MISMATCH', 'ENFORCEMENT_ERROR')),
    CONSTRAINT uq_web_event_client UNIQUE (device_id, client_event_id)
);
CREATE INDEX idx_web_event_device_time ON web_access_events (device_id, occurred_at DESC);
CREATE INDEX idx_web_event_employee_time ON web_access_events (employee_id, occurred_at DESC);
CREATE INDEX idx_web_event_domain ON web_access_events (domain);
CREATE INDEX idx_web_event_type ON web_access_events (event_type, occurred_at DESC);
