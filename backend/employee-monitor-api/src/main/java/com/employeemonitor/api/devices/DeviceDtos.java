package com.employeemonitor.api.devices;

import com.employeemonitor.api.employees.EmployeeDtos.EmployeeSummary;
import com.employeemonitor.api.policies.PolicySettings;
import com.employeemonitor.api.presence.ActivityStatus;
import com.employeemonitor.api.presence.Presence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class DeviceDtos {

    private DeviceDtos() {
    }

    // ---------- Enrollment ----------

    public record CreateEnrollmentTokenRequest(
            @Size(max = 255) String description,
            Long employeeId,
            @Min(1) @Max(1000) Integer maxUses,
            @Min(1) @Max(24 * 30) Integer expiresInHours) {
    }

    /** Returned once at creation; the plaintext token is never retrievable again. */
    public record EnrollmentTokenCreatedResponse(Long id, String token, String description,
                                                 EmployeeSummary employee, int maxUses, Instant expiresAt) {
    }

    public record EnrollmentTokenResponse(Long id, String description, EmployeeSummary employee, int maxUses,
                                          int useCount, Instant expiresAt, Instant revokedAt, Instant createdAt,
                                          boolean usable) {
        public static EnrollmentTokenResponse from(EnrollmentToken t, Instant now) {
            return new EnrollmentTokenResponse(t.getId(), t.getDescription(), EmployeeSummary.from(t.getEmployee()),
                    t.getMaxUses(), t.getUseCount(), t.getExpiresAt(), t.getRevokedAt(), t.getCreatedAt(),
                    t.isUsable(now));
        }
    }

    // ---------- Registration (agent / installer) ----------

    public record RegisterDeviceRequest(
            @NotBlank @Size(max = 200) String enrollmentToken,
            @NotBlank @Size(max = 255) String hostname,
            @Size(max = 120) String osVersion,
            @NotBlank @Size(max = 40) String agentVersion,
            @Size(max = 64) String macAddress,
            @Size(max = 64) String ipAddress,
            @Size(max = 128) String hardwareId) {
    }

    public record DeviceRegistrationResponse(
            Long deviceId,
            String deviceCode,
            String deviceToken,
            Instant deviceTokenExpiresAt,
            EmployeeSummary employee,
            String policyRevision,
            PolicySettings policy) {
    }

    // ---------- Admin ----------

    public record AssignDeviceRequest(Long employeeId) {
    }

    public record UpdateDeviceStatusRequest(@NotNull Device.Status status) {
    }

    public record PresenceResponse(ActivityStatus status, boolean online, Instant lastHeartbeatAt,
                                   String currentApplication, String network, String zscalerStatus) {
        public static PresenceResponse from(Presence p) {
            return new PresenceResponse(p.status(), p.online(), p.lastHeartbeatAt(),
                    p.currentApplication(), p.network(), p.zscalerStatus());
        }
    }

    public record DeviceResponse(Long id, String deviceCode, String hostname, String osVersion, String agentVersion,
                                 String macAddress, String ipAddress, Device.Status status,
                                 EmployeeSummary employee, Long policyId, Instant registeredAt,
                                 Instant lastSeenAt, PresenceResponse presence) {
        public static DeviceResponse from(Device d, Presence presence) {
            Presence p = presence != null ? presence
                    : Presence.offline(d.getId(), d.getEmployee() == null ? null : d.getEmployee().getId(),
                    d.getLastSeenAt());
            return new DeviceResponse(d.getId(), d.getDeviceCode(), d.getHostname(), d.getOsVersion(),
                    d.getAgentVersion(), d.getMacAddress(), d.getIpAddress(), d.getStatus(),
                    EmployeeSummary.from(d.getEmployee()), d.getPolicyId(), d.getRegisteredAt(), d.getLastSeenAt(),
                    PresenceResponse.from(p));
        }
    }

    // ---------- Agent runtime ----------

    public record HeartbeatRequest(
            @NotNull ActivityStatus status,
            Instant timestamp,
            @Size(max = 40) String agentVersion,
            @Size(max = 64) String ipAddress,
            // Optional live snapshot for the dashboard (policy-gated on the agent side).
            @Size(max = 255) String currentApplication,
            @Size(max = 255) String network,
            @Size(max = 20) String zscalerStatus) {
    }

    public record HeartbeatResponse(Instant serverTime, String policyRevision, int nextHeartbeatSeconds) {
    }

    public record AgentIdentityResponse(Long deviceId, String deviceCode, String hostname, EmployeeSummary employee,
                                        String policyRevision) {
    }
}
