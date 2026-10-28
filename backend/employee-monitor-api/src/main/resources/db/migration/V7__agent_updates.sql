-- =====================================================================
-- V7: Signed agent auto-update (Milestone 7).
-- The agent_versions table already exists (V1); add the fields the update
-- flow needs. Packages are cryptographically signed; the agent verifies the
-- SHA-256 and the RSA signature against its embedded public key before
-- installing. The backend stores and serves them but never holds the key.
-- =====================================================================

ALTER TABLE agent_versions
    ADD COLUMN size_bytes   BIGINT,
    ADD COLUMN content_type VARCHAR(80) DEFAULT 'application/octet-stream',
    ADD COLUMN created_by   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN mandatory    BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_agent_versions_approved ON agent_versions (approved, released_at DESC);

-- New permission for agent/version management; granted to SUPER_ADMIN and IT_ADMIN.
INSERT INTO permissions (name, description) VALUES
    ('AGENT_MANAGE', 'Publish and approve agent versions');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.name = 'AGENT_MANAGE'
WHERE r.name IN ('SUPER_ADMIN', 'IT_ADMIN');
