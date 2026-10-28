package com.employeemonitor.api.users;

/**
 * Permission names (seeded in V2 migration) and role names. Permissions are granted to
 * authenticated users as Spring authorities and checked with {@code @PreAuthorize}.
 */
public final class Permissions {

    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String EMPLOYEE_READ_ALL = "EMPLOYEE_READ_ALL";
    public static final String EMPLOYEE_READ_ASSIGNED = "EMPLOYEE_READ_ASSIGNED";
    public static final String EMPLOYEE_WRITE = "EMPLOYEE_WRITE";
    public static final String DEPARTMENT_WRITE = "DEPARTMENT_WRITE";
    public static final String DEVICE_READ = "DEVICE_READ";
    public static final String DEVICE_WRITE = "DEVICE_WRITE";
    public static final String ENROLLMENT_MANAGE = "ENROLLMENT_MANAGE";
    public static final String POLICY_READ = "POLICY_READ";
    public static final String POLICY_WRITE = "POLICY_WRITE";
    public static final String AUDIT_READ = "AUDIT_READ";
    public static final String REPORT_READ = "REPORT_READ";
    public static final String SELF_READ = "SELF_READ";
    public static final String LIVE_VIEW = "LIVE_VIEW";
    public static final String AGENT_MANAGE = "AGENT_MANAGE";
    public static final String ALERT_READ = "ALERT_READ";
    public static final String WEB_POLICY_READ = "WEB_POLICY_READ";
    public static final String WEB_POLICY_MANAGE = "WEB_POLICY_MANAGE";
    public static final String WEB_EVENT_READ = "WEB_EVENT_READ";

    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ROLE_IT_ADMIN = "IT_ADMIN";
    public static final String ROLE_HR_ADMIN = "HR_ADMIN";
    public static final String ROLE_MANAGER = "MANAGER";
    public static final String ROLE_EMPLOYEE = "EMPLOYEE";

    private Permissions() {
    }
}
