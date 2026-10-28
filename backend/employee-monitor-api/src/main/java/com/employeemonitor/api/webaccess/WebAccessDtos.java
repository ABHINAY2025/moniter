package com.employeemonitor.api.webaccess;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WebAccessDtos {

    private WebAccessDtos() {
    }

    // ---------- Policy management ----------

    public record RuleInput(String domain, String category, @NotNull WebAction action) {
    }

    public record CreateWebPolicyRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description,
            Boolean enabled,
            @Min(1) @Max(1000) Integer priority,
            Instant validFrom,
            Instant validTo,
            boolean overrideCriticalDomains,
            @NotNull List<RuleInput> rules) {
    }

    public record UpdateWebPolicyRequest(
            @Size(max = 120) String name,
            @Size(max = 500) String description,
            Boolean enabled,
            @Min(1) @Max(1000) Integer priority,
            Instant validFrom,
            Instant validTo,
            boolean overrideCriticalDomains,
            List<RuleInput> rules) {
    }

    public record RuleResponse(Long id, String domain, String category, WebAction action) {
        public static RuleResponse from(WebPolicyRule r) {
            return new RuleResponse(r.getId(), r.getDomain(), r.getCategory(), r.getAction());
        }
    }

    public record WebPolicyResponse(Long id, String name, String description, boolean enabled, int priority,
                                    Instant validFrom, Instant validTo, int revision, List<RuleResponse> rules,
                                    long assignmentCount, Instant createdAt, Instant updatedAt) {
    }

    public record AssignRequest(@NotNull WebPolicyAssignment.TargetType targetType, @NotNull Long targetId) {
    }

    public record AssignmentResponse(Long id, WebPolicyAssignment.TargetType targetType, Long targetId,
                                     String targetLabel, Instant createdAt) {
    }

    // ---------- Agent sync ----------

    /** The effective, resolved rule set a device must enforce, plus a content hash used as the sync revision. */
    public record EffectiveWebPolicyResponse(String revision, List<String> block, List<String> allow,
                                             List<String> monitor, Instant generatedAt) {
    }

    /** Agent -> server: enforcement health report. */
    public record WebEnforcementReport(String mechanism, boolean healthy, String appliedRevision, String browsers,
                                       String error) {
    }

    public record WebEnforcementStatusResponse(Long deviceId, String mechanism, boolean healthy,
                                               String appliedRevision, String effectiveRevision, String browsers,
                                               Instant lastSyncAt, Instant lastEventAt, String lastError,
                                               boolean stale) {
    }

    // ---------- Access events ----------

    public record WebAccessEventInput(UUID clientEventId, @NotBlank String domain, String browser, String eventType,
                                      String source, @NotNull Instant occurredAt) {
    }

    public record WebAccessEventBatch(@NotNull @Size(max = 500) List<WebAccessEventInput> events) {
    }

    public record WebAccessEventResponse(Long id, Long deviceId, String deviceCode, Long employeeId,
                                         String employeeName, String domain, String browser, String eventType,
                                         String enforcementResult, Long matchedPolicyId, String source,
                                         Instant occurredAt) {
    }

    public record WebAccessIngestResult(int accepted, int duplicates, int rejected) {
    }

    public record DomainCount(String domain, long count) {
    }

    public record WebAccessSummaryResponse(long observed, long allowed, long blocked, long monitored,
                                           long enforcementErrors, List<DomainCount> topBlocked) {
    }
}
