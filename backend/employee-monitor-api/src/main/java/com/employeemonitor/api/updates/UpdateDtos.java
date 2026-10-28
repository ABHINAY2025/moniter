package com.employeemonitor.api.updates;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class UpdateDtos {

    private UpdateDtos() {
    }

    public record PublishRequest(
            @NotBlank @Size(max = 40) String version,
            @Size(max = 2000) String notes,
            boolean mandatory,
            @NotBlank String signature,
            @NotBlank @Size(max = 300_000_000) String packageBase64) {
    }

    public record ApprovalRequest(@NotNull Boolean approved) {
    }

    public record VersionResponse(Long id, String version, boolean approved, boolean mandatory, Long sizeBytes,
                                  String sha256, Instant releasedAt, String notes) {
        public static VersionResponse from(AgentVersion v) {
            return new VersionResponse(v.getId(), v.getVersion(), v.isApproved(), v.isMandatory(), v.getSizeBytes(),
                    v.getSha256(), v.getReleasedAt(), v.getNotes());
        }
    }

    /** Returned to the agent when a newer approved version exists; the agent verifies sha256 + signature. */
    public record UpdateAvailableResponse(String version, String sha256, String signature, boolean mandatory,
                                          long sizeBytes, String downloadPath) {
    }
}
