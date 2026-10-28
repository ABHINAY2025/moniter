-- =====================================================================
-- V1: Core schema - identity, RBAC, employees, devices, policies, audit
-- =====================================================================

-- ---------- RBAC ----------
CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL UNIQUE,
    description VARCHAR(255)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ---------- Organisation ----------
CREATE TABLE departments (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE employees (
    id            BIGSERIAL PRIMARY KEY,
    employee_code VARCHAR(40)  NOT NULL UNIQUE,
    full_name     VARCHAR(160) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    department_id BIGINT REFERENCES departments (id) ON DELETE SET NULL,
    job_title     VARCHAR(120),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_employee_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX idx_employees_department ON employees (department_id);
CREATE INDEX idx_employees_name_lower ON employees (lower(full_name));

-- ---------- Users (people who log into the admin app / self-service) ----------
CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(80)  NOT NULL UNIQUE,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    full_name             VARCHAR(160) NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    employee_id           BIGINT UNIQUE REFERENCES employees (id) ON DELETE SET NULL,
    failed_login_attempts INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE refresh_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)    NOT NULL UNIQUE,
    family_id   UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by BIGINT REFERENCES refresh_tokens (id),
    created_ip  VARCHAR(64)
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);

-- Managers only see employees explicitly assigned to them.
CREATE TABLE manager_assignments (
    manager_user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    employee_id        BIGINT      NOT NULL REFERENCES employees (id) ON DELETE CASCADE,
    live_view_allowed  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by         BIGINT REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (manager_user_id, employee_id)
);
CREATE INDEX idx_manager_assignments_employee ON manager_assignments (employee_id);

-- ---------- Monitoring policies ----------
CREATE TABLE monitoring_policies (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL UNIQUE,
    is_default BOOLEAN      NOT NULL DEFAULT FALSE,
    -- TRUE when the policy is an override owned by a single device.
    device_specific BOOLEAN NOT NULL DEFAULT FALSE,
    settings   JSONB        NOT NULL,
    version    BIGINT       NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- At most one default policy.
CREATE UNIQUE INDEX uq_monitoring_policies_default ON monitoring_policies (is_default) WHERE is_default;

-- ---------- Devices ----------
CREATE SEQUENCE device_code_seq START 10001;

CREATE TABLE devices (
    id             BIGSERIAL PRIMARY KEY,
    device_code    VARCHAR(20)  NOT NULL UNIQUE,
    hostname       VARCHAR(255) NOT NULL,
    os_version     VARCHAR(120),
    agent_version  VARCHAR(40),
    mac_address    VARCHAR(64),
    ip_address     VARCHAR(64),
    hardware_id    VARCHAR(128),
    employee_id    BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    policy_id      BIGINT REFERENCES monitoring_policies (id) ON DELETE SET NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    registered_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_device_status CHECK (status IN ('ACTIVE', 'DISABLED', 'RETIRED'))
);
CREATE INDEX idx_devices_employee ON devices (employee_id);
CREATE INDEX idx_devices_hostname_lower ON devices (lower(hostname));
CREATE INDEX idx_devices_last_seen ON devices (last_seen_at);

-- Long-lived device credentials; only SHA-256 hashes are stored.
CREATE TABLE device_tokens (
    id           BIGSERIAL PRIMARY KEY,
    device_id    BIGINT      NOT NULL REFERENCES devices (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64)    NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_device_tokens_device ON device_tokens (device_id);

-- One-time / limited-use codes the installer presents to enroll a device.
CREATE TABLE enrollment_tokens (
    id          BIGSERIAL PRIMARY KEY,
    token_hash  VARCHAR(64)     NOT NULL UNIQUE,
    description VARCHAR(255),
    employee_id BIGINT REFERENCES employees (id) ON DELETE SET NULL,
    max_uses    INT          NOT NULL DEFAULT 1,
    use_count   INT          NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_by  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_enrollment_max_uses CHECK (max_uses BETWEEN 1 AND 1000)
);

-- ---------- Agent versions (used by auto-update in a later milestone) ----------
CREATE TABLE agent_versions (
    id           BIGSERIAL PRIMARY KEY,
    version      VARCHAR(40)  NOT NULL UNIQUE,
    download_key VARCHAR(512),
    sha256       VARCHAR(64),
    signature    TEXT,
    approved     BOOLEAN      NOT NULL DEFAULT FALSE,
    released_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    notes        TEXT
);

-- ---------- Audit log ----------
CREATE TABLE audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    actor_user_id  BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_username VARCHAR(80),
    action         VARCHAR(80)  NOT NULL,
    target_type    VARCHAR(40),
    target_id      VARCHAR(64),
    outcome        VARCHAR(20)  NOT NULL DEFAULT 'SUCCESS',
    ip_address     VARCHAR(64),
    metadata       JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at DESC);
CREATE INDEX idx_audit_logs_actor ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX idx_audit_logs_action ON audit_logs (action, created_at DESC);
CREATE INDEX idx_audit_logs_target ON audit_logs (target_type, target_id);
