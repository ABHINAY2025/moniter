package com.employeemonitor.api.audit;

/** Audited action names. Stored as strings so new actions never need a migration. */
public final class AuditAction {

    public static final String LOGIN_SUCCESS = "AUTH_LOGIN_SUCCESS";
    public static final String LOGIN_FAILURE = "AUTH_LOGIN_FAILURE";
    public static final String LOGOUT = "AUTH_LOGOUT";
    public static final String REFRESH_TOKEN_REUSE = "AUTH_REFRESH_TOKEN_REUSE";
    public static final String PASSWORD_CHANGED = "AUTH_PASSWORD_CHANGED";

    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_UPDATED = "USER_UPDATED";
    public static final String MANAGER_ASSIGNMENT_SET = "MANAGER_ASSIGNMENT_SET";
    public static final String MANAGER_ASSIGNMENT_REMOVED = "MANAGER_ASSIGNMENT_REMOVED";

    public static final String EMPLOYEE_VIEWED = "EMPLOYEE_VIEWED";
    public static final String EMPLOYEE_CREATED = "EMPLOYEE_CREATED";
    public static final String EMPLOYEE_UPDATED = "EMPLOYEE_UPDATED";
    public static final String DEPARTMENT_CREATED = "DEPARTMENT_CREATED";
    public static final String DEPARTMENT_UPDATED = "DEPARTMENT_UPDATED";

    public static final String ENROLLMENT_TOKEN_CREATED = "ENROLLMENT_TOKEN_CREATED";
    public static final String ENROLLMENT_TOKEN_REVOKED = "ENROLLMENT_TOKEN_REVOKED";
    public static final String DEVICE_REGISTERED = "DEVICE_REGISTERED";
    public static final String DEVICE_REGISTRATION_FAILED = "DEVICE_REGISTRATION_FAILED";
    public static final String DEVICE_ASSIGNED = "DEVICE_ASSIGNED";
    public static final String DEVICE_STATUS_CHANGED = "DEVICE_STATUS_CHANGED";
    public static final String DEVICE_REMOVED = "DEVICE_REMOVED";

    public static final String POLICY_CHANGED = "POLICY_CHANGED";

    public static final String SCREENSHOT_VIEWED = "SCREENSHOT_VIEWED";
    public static final String SCREENSHOTS_PURGED = "SCREENSHOTS_PURGED";

    public static final String LIVE_VIEW_STARTED = "LIVE_VIEW_STARTED";
    public static final String LIVE_VIEW_STOPPED = "LIVE_VIEW_STOPPED";

    public static final String WEB_POLICY_CREATED = "WEB_POLICY_CREATED";
    public static final String WEB_POLICY_UPDATED = "WEB_POLICY_UPDATED";
    public static final String WEB_POLICY_DELETED = "WEB_POLICY_DELETED";
    public static final String WEB_POLICY_ASSIGNED = "WEB_POLICY_ASSIGNED";
    public static final String WEB_POLICY_UNASSIGNED = "WEB_POLICY_UNASSIGNED";

    private AuditAction() {
    }
}
