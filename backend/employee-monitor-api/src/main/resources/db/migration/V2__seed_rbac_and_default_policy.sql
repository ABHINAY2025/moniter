-- =====================================================================
-- V2: Seed roles, permissions and the default monitoring policy.
-- No user accounts are seeded; the first SUPER_ADMIN is created at
-- startup from environment variables (see BootstrapAdminInitializer).
-- =====================================================================

INSERT INTO roles (name, description) VALUES
    ('SUPER_ADMIN', 'Full platform access'),
    ('IT_ADMIN',    'Devices, network, USB and agent management'),
    ('HR_ADMIN',    'Employees, attendance and reports'),
    ('MANAGER',     'Explicitly assigned employees only'),
    ('EMPLOYEE',    'Own monitoring status, device and policy');

INSERT INTO permissions (name, description) VALUES
    ('USER_MANAGE',          'Create users, change roles, manage manager assignments'),
    ('EMPLOYEE_READ_ALL',    'Read every employee record'),
    ('EMPLOYEE_READ_ASSIGNED','Read employees assigned to the current manager'),
    ('EMPLOYEE_WRITE',       'Create, update and deactivate employees'),
    ('DEPARTMENT_WRITE',     'Create and update departments'),
    ('DEVICE_READ',          'Read device inventory'),
    ('DEVICE_WRITE',         'Assign, disable, retire devices'),
    ('ENROLLMENT_MANAGE',    'Issue and revoke device enrollment tokens'),
    ('POLICY_READ',          'Read monitoring policies'),
    ('POLICY_WRITE',         'Change monitoring policies'),
    ('AUDIT_READ',           'Read audit logs'),
    ('REPORT_READ',          'Read dashboards and reports'),
    ('SELF_READ',            'Read own employee record, device and policy');

-- SUPER_ADMIN: everything
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.name = 'SUPER_ADMIN';

-- IT_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('EMPLOYEE_READ_ALL', 'DEVICE_READ', 'DEVICE_WRITE', 'ENROLLMENT_MANAGE',
                  'POLICY_READ', 'POLICY_WRITE', 'REPORT_READ', 'SELF_READ')
WHERE r.name = 'IT_ADMIN';

-- HR_ADMIN
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('EMPLOYEE_READ_ALL', 'EMPLOYEE_WRITE', 'DEPARTMENT_WRITE',
                  'POLICY_READ', 'REPORT_READ', 'SELF_READ')
WHERE r.name = 'HR_ADMIN';

-- MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('EMPLOYEE_READ_ASSIGNED', 'REPORT_READ', 'SELF_READ')
WHERE r.name = 'MANAGER';

-- EMPLOYEE
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.name IN ('SELF_READ')
WHERE r.name = 'EMPLOYEE';

INSERT INTO monitoring_policies (name, is_default, settings) VALUES (
    'Default policy',
    TRUE,
    '{
        "sessionMonitoring": true,
        "idleThresholdSeconds": 300,
        "applicationMonitoring": true,
        "websiteMonitoring": false,
        "usbMonitoring": true,
        "networkMonitoring": true,
        "zscalerMonitoring": true,
        "screenshots": false,
        "screenshotIntervalSeconds": 900,
        "liveView": false,
        "liveViewMaxMinutes": 30,
        "heartbeatIntervalSeconds": 30,
        "policySyncIntervalSeconds": 300,
        "monitoringStart": "09:00",
        "monitoringEnd": "18:00",
        "workDays": ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"],
        "timezone": "UTC",
        "retentionDays": 90,
        "screenshotRetentionDays": 30
    }'::jsonb
);
