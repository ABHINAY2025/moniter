package com.employeemonitor.api.policies;

import java.time.Instant;

public final class PolicyDtos {

    private PolicyDtos() {
    }

    /**
     * @param revision opaque "{policyId}-{version}" tag; agents re-download the policy whenever it changes.
     * @param source   DEFAULT, DEVICE_OVERRIDE or SHARED
     */
    public record PolicyResponse(Long policyId, String name, String source, String revision,
                                 PolicySettings settings, Instant updatedAt) {
        public static PolicyResponse from(MonitoringPolicy p) {
            String source = p.isDefaultPolicy() ? "DEFAULT" : p.isDeviceSpecific() ? "DEVICE_OVERRIDE" : "SHARED";
            return new PolicyResponse(p.getId(), p.getName(), source, PolicyDtos.revision(p), p.getSettings(), p.getUpdatedAt());
        }
    }

    public static String revision(MonitoringPolicy p) {
        return p.getId() + "-" + p.getVersion();
    }
}
