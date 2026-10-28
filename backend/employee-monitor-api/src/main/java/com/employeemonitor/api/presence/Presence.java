package com.employeemonitor.api.presence;

import java.time.Instant;

/** Current realtime state of a device. */
public record Presence(Long deviceId, Long employeeId, ActivityStatus status, Instant lastHeartbeatAt,
                       String ipAddress, String currentApplication, String network, String zscalerStatus) {

    public static Presence offline(Long deviceId, Long employeeId, Instant lastSeenAt) {
        return new Presence(deviceId, employeeId, ActivityStatus.OFFLINE, lastSeenAt, null, null, null, null);
    }

    public boolean online() {
        return status != ActivityStatus.OFFLINE;
    }
}
