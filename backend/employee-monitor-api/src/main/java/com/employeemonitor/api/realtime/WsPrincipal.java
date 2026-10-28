package com.employeemonitor.api.realtime;

import java.util.Set;

/**
 * Visibility snapshot captured at WebSocket handshake. Determines which presence events a connection receives.
 *
 * @param seeAll      true for users with EMPLOYEE_READ_ALL (admins / IT / HR)
 * @param employeeIds the exact employees a scoped user (manager / employee) may see; ignored when seeAll
 */
public record WsPrincipal(Long userId, String username, boolean seeAll, Set<Long> employeeIds) {

    public boolean canSee(Long employeeId) {
        if (seeAll) {
            return true;
        }
        return employeeId != null && employeeIds.contains(employeeId);
    }
}
