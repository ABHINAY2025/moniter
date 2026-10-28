package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.security.DevicePrincipal;
import com.employeemonitor.api.webaccess.WebAccessDtos.DomainCount;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessEventInput;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessEventResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessIngestResult;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebAccessSummaryResponse;
import com.employeemonitor.api.webaccess.WebPolicyEvaluator.Decision;
import com.employeemonitor.api.webaccess.WebPolicyEvaluator.EffectiveRule;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ingests domain-level web-access events from devices and answers admin queries. The server is authoritative on the
 * enforcement result: it re-evaluates each reported domain against the device's effective policy and only labels a
 * navigation BLOCKED when a block rule applies AND the device reports healthy enforcement - it never invents a page
 * load from a DNS observation.
 */
@Service
public class WebAccessEventService {

    private static final Set<String> VALID_EVENT_TYPES = Set.of(
            "NAVIGATION_OBSERVED", "ACCESS_ATTEMPT", "ACCESS_ALLOWED", "ACCESS_BLOCKED",
            "POLICY_MISMATCH", "ENFORCEMENT_ERROR");

    private final WebAccessEventRepository events;
    private final WebPolicyService policyService;
    private final WebEnforcementStatusRepository statuses;
    private final DeviceRepository devices;
    private final EmployeeRepository employees;
    private final WebRealtimePublisher realtime;
    private final Clock clock;

    public WebAccessEventService(WebAccessEventRepository events, WebPolicyService policyService,
                                 WebEnforcementStatusRepository statuses, DeviceRepository devices,
                                 EmployeeRepository employees, WebRealtimePublisher realtime, Clock clock) {
        this.events = events;
        this.policyService = policyService;
        this.statuses = statuses;
        this.devices = devices;
        this.employees = employees;
        this.realtime = realtime;
        this.clock = clock;
    }

    @Transactional
    public WebAccessIngestResult ingest(DevicePrincipal principal, List<WebAccessEventInput> batch) {
        Device device = devices.findById(principal.deviceId()).orElse(null);
        if (device == null) {
            return new WebAccessIngestResult(0, 0, batch.size());
        }
        List<EffectiveRule> effective = policyService.resolve(device).rules();
        boolean enforcementHealthy = statuses.findById(device.getId())
                .map(WebEnforcementStatus::isHealthy).orElse(false);

        // de-dup against already-stored client event ids
        Set<UUID> incomingIds = new HashSet<>();
        for (WebAccessEventInput e : batch) {
            if (e.clientEventId() != null) {
                incomingIds.add(e.clientEventId());
            }
        }
        Set<UUID> existing = incomingIds.isEmpty() ? Set.of()
                : events.findExistingClientIds(device.getId(), incomingIds);

        int accepted = 0;
        int duplicates = 0;
        int rejected = 0;
        Instant now = clock.instant();
        Set<UUID> seen = new HashSet<>();
        List<WebAccessEvent> toSave = new ArrayList<>();

        for (WebAccessEventInput in : batch) {
            String domain = DomainMatcher.canonicalize(in.domain());
            if (domain.isEmpty() || in.occurredAt() == null || in.occurredAt().isAfter(now.plusSeconds(300))) {
                rejected++;
                continue;
            }
            if (in.clientEventId() != null) {
                if (existing.contains(in.clientEventId()) || !seen.add(in.clientEventId())) {
                    duplicates++;
                    continue;
                }
            }
            Decision decision = WebPolicyEvaluator.decide(effective, domain);
            String result = switch (decision.action() == null ? WebAction.MONITOR : decision.action()) {
                case BLOCK -> enforcementHealthy ? "BLOCKED" : "NONE";
                case ALLOW -> "ALLOWED";
                case MONITOR -> "MONITORED";
            };
            String eventType = in.eventType() != null && VALID_EVENT_TYPES.contains(in.eventType())
                    ? in.eventType() : "NAVIGATION_OBSERVED";
            toSave.add(new WebAccessEvent(in.clientEventId(), device.getId(),
                    device.getEmployee() == null ? null : device.getEmployee().getId(), domain, in.browser(),
                    eventType, result, decision.policyId(), decision.ruleId(),
                    in.source() == null ? "DNS_LOG" : in.source(), in.occurredAt()));
            accepted++;
        }

        if (!toSave.isEmpty()) {
            events.saveAll(toSave);
            statuses.findById(device.getId()).ifPresent(s -> {
                s.touchEvent(now);
                statuses.save(s);
            });
            // publish only the restricted (blocked) ones live, to keep the stream meaningful
            Long employeeId = device.getEmployee() == null ? null : device.getEmployee().getId();
            for (WebAccessEvent e : toSave) {
                if ("BLOCKED".equals(e.getEnforcementResult())) {
                    realtime.publish("WEB_EVENT", eventEnvelope(device, e, employeeId));
                }
            }
        }
        return new WebAccessIngestResult(accepted, duplicates, rejected);
    }

    // PostgreSQL cannot infer the type of a NULL bind used only in "(:param is null or ...)" for timestamps
    // (ERROR 42P18), so the time-window bounds are always passed as concrete, typed values ("unbounded" = the
    // full representable range) rather than as NULL.
    private static final Instant TIME_MIN = Instant.EPOCH;
    private static final Instant TIME_MAX = Instant.parse("9999-12-31T23:59:59Z");

    @Transactional(readOnly = true)
    public PageResponse<WebAccessEventResponse> query(Long deviceId, Long employeeId, String eventType, String result,
                                                      String domain, Instant from, Instant to, Pageable pageable) {
        var page = events.search(deviceId, employeeId, eventType, result,
                domain == null || domain.isBlank() ? null : DomainMatcher.canonicalize(domain),
                from != null ? from : TIME_MIN, to != null ? to : TIME_MAX, pageable);
        return PageResponse.of(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public WebAccessEventResponse getById(Long id) {
        return events.findById(id).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("Web access event", id));
    }

    @Transactional(readOnly = true)
    public WebAccessSummaryResponse summary(Instant since) {
        Map<String, Long> byType = new HashMap<>();
        for (Object[] row : events.countByEventTypeSince(since)) {
            byType.put((String) row[0], (Long) row[1]);
        }
        long observed = byType.values().stream().mapToLong(Long::longValue).sum();
        List<DomainCount> topBlocked = new ArrayList<>();
        for (Object[] row : events.topBlockedDomainsSince(since, Pageable.ofSize(10))) {
            topBlocked.add(new DomainCount((String) row[0], (Long) row[1]));
        }
        // allowed/blocked/monitored counts come from a result breakdown
        long blocked = topBlocked.stream().mapToLong(DomainCount::count).sum();
        return new WebAccessSummaryResponse(observed,
                byType.getOrDefault("ACCESS_ALLOWED", 0L),
                blocked,
                byType.getOrDefault("NAVIGATION_OBSERVED", 0L),
                byType.getOrDefault("ENFORCEMENT_ERROR", 0L),
                topBlocked);
    }

    private WebAccessEventResponse toResponse(WebAccessEvent e) {
        String deviceCode = devices.findById(e.getDeviceId()).map(Device::getDeviceCode).orElse(null);
        String employeeName = e.getEmployeeId() == null ? null
                : employees.findById(e.getEmployeeId()).map(Employee::getFullName).orElse(null);
        return new WebAccessEventResponse(e.getId(), e.getDeviceId(), deviceCode, e.getEmployeeId(), employeeName,
                e.getDomain(), e.getBrowser(), e.getEventType(), e.getEnforcementResult(), e.getMatchedPolicyId(),
                e.getSource(), e.getOccurredAt());
    }

    private Map<String, Object> eventEnvelope(Device device, WebAccessEvent e, Long employeeId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", device.getId());
        m.put("deviceCode", device.getDeviceCode());
        m.put("employeeId", employeeId);
        m.put("domain", e.getDomain());
        m.put("eventType", e.getEventType());
        m.put("enforcementResult", e.getEnforcementResult());
        m.put("occurredAt", e.getOccurredAt().toString());
        return m;
    }
}
