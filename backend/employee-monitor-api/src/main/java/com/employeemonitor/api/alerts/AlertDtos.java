package com.employeemonitor.api.alerts;

import java.time.Instant;

public final class AlertDtos {

    private AlertDtos() {
    }

    public record AlertResponse(Long id, String category, String severity, String message, Long deviceId,
                                Long employeeId, String deviceCode, String employeeName, Instant createdAt,
                                boolean acknowledged, Instant acknowledgedAt) {

        public static AlertResponse from(Alert a) {
            return new AlertResponse(a.getId(), a.getCategory(), a.getSeverity(), a.getMessage(), a.getDeviceId(),
                    a.getEmployeeId(), a.getDeviceCode(), a.getEmployeeName(), a.getCreatedAt(),
                    a.getAcknowledgedAt() != null, a.getAcknowledgedAt());
        }
    }
}
