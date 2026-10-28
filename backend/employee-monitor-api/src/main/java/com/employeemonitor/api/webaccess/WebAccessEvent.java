package com.employeemonitor.api.webaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** A domain-level website access event reported by a device. Domains only - never full URLs, content or secrets. */
@Entity
@Table(name = "web_access_events")
public class WebAccessEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id")
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(nullable = false)
    private String domain;

    private String browser;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "enforcement_result")
    private String enforcementResult;

    @Column(name = "matched_policy_id")
    private Long matchedPolicyId;

    @Column(name = "matched_rule_id")
    private Long matchedRuleId;

    private String source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected WebAccessEvent() {
    }

    public WebAccessEvent(UUID clientEventId, Long deviceId, Long employeeId, String domain, String browser,
                          String eventType, String enforcementResult, Long matchedPolicyId, Long matchedRuleId,
                          String source, Instant occurredAt) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.domain = domain;
        this.browser = browser;
        this.eventType = eventType;
        this.enforcementResult = enforcementResult;
        this.matchedPolicyId = matchedPolicyId;
        this.matchedRuleId = matchedRuleId;
        this.source = source;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getClientEventId() {
        return clientEventId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getDomain() {
        return domain;
    }

    public String getBrowser() {
        return browser;
    }

    public String getEventType() {
        return eventType;
    }

    public String getEnforcementResult() {
        return enforcementResult;
    }

    public Long getMatchedPolicyId() {
        return matchedPolicyId;
    }

    public Long getMatchedRuleId() {
        return matchedRuleId;
    }

    public String getSource() {
        return source;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
