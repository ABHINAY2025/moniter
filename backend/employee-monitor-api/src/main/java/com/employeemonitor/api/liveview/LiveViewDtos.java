package com.employeemonitor.api.liveview;

import com.employeemonitor.api.employees.EmployeeDtos.EmployeeSummary;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class LiveViewDtos {

    private LiveViewDtos() {
    }

    public record CreateRequest(@NotNull Long deviceId) {
    }

    public record SessionResponse(UUID sessionId, Long deviceId, String deviceCode, EmployeeSummary employee,
                                  String status, Instant startedAt, int maxDurationSeconds, String signalingPath) {
    }

    public record SessionSummary(UUID sessionId, String adminUsername, Long deviceId, Long employeeId, String status,
                                 Instant startedAt, Instant endedAt, Integer durationSeconds, String endReason) {
        public static SessionSummary from(LiveViewSession s) {
            return new SessionSummary(s.getSessionId(), s.getAdminUsername(), s.getDeviceId(), s.getEmployeeId(),
                    s.getStatus().name(), s.getStartedAt(), s.getEndedAt(), s.getDurationSeconds(), s.getEndReason());
        }
    }

    /** Agent/tray indicator state for a device. */
    public record AgentLiveViewStatus(boolean active, UUID sessionId) {
    }
}
