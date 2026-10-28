package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.webaccess.WebAccessDtos.AssignmentResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.CreateWebPolicyRequest;
import com.employeemonitor.api.webaccess.WebAccessDtos.EffectiveWebPolicyResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.RuleInput;
import com.employeemonitor.api.webaccess.WebAccessDtos.RuleResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.UpdateWebPolicyRequest;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebEnforcementReport;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebEnforcementStatusResponse;
import com.employeemonitor.api.webaccess.WebAccessDtos.WebPolicyResponse;
import com.employeemonitor.api.webaccess.WebPolicyAssignment.TargetType;
import com.employeemonitor.api.webaccess.WebPolicyEvaluator.EffectiveRule;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class WebPolicyService {

    private final WebPolicyRepository policies;
    private final WebPolicyRuleRepository rules;
    private final WebPolicyAssignmentRepository assignments;
    private final WebEnforcementStatusRepository statuses;
    private final DeviceRepository devices;
    private final EmployeeRepository employees;
    private final AuditService audit;
    private final WebRealtimePublisher realtime;
    private final Clock clock;

    public WebPolicyService(WebPolicyRepository policies, WebPolicyRuleRepository rules,
                            WebPolicyAssignmentRepository assignments, WebEnforcementStatusRepository statuses,
                            DeviceRepository devices, EmployeeRepository employees, AuditService audit,
                            WebRealtimePublisher realtime, Clock clock) {
        this.policies = policies;
        this.rules = rules;
        this.assignments = assignments;
        this.statuses = statuses;
        this.devices = devices;
        this.employees = employees;
        this.audit = audit;
        this.realtime = realtime;
        this.clock = clock;
    }

    // ---------- CRUD ----------

    @Transactional(readOnly = true)
    public PageResponse<WebPolicyResponse> list(String q, Pageable pageable) {
        var page = (q == null || q.isBlank())
                ? policies.findAll(pageable)
                : policies.findByNameContainingIgnoreCase(q.trim(), pageable);
        return PageResponse.of(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public WebPolicyResponse get(Long id) {
        return toResponse(load(id));
    }

    @Transactional
    public WebPolicyResponse create(CreateWebPolicyRequest req, Long actorId) {
        var validated = validateRules(req.rules(), req.overrideCriticalDomains());
        WebPolicy policy = new WebPolicy(req.name().trim(),
                req.description() == null ? null : req.description().trim(),
                req.priority() == null ? 100 : req.priority(), actorId);
        if (req.enabled() != null) {
            policy.setEnabled(req.enabled());
        }
        policy.setValidFrom(req.validFrom());
        policy.setValidTo(req.validTo());
        policy = policies.save(policy);
        saveRules(policy.getId(), validated);
        audit.record(AuditAction.WEB_POLICY_CREATED, "WEB_POLICY", policy.getId(),
                Map.of("name", policy.getName(), "rules", validated.size()));
        realtime.publish("WEB_POLICY", envelope(policy.getId(), "created"));
        return toResponse(policy);
    }

    @Transactional
    public WebPolicyResponse update(Long id, UpdateWebPolicyRequest req, Long actorId) {
        WebPolicy policy = load(id);
        if (req.name() != null) {
            policy.setName(req.name().trim());
        }
        if (req.description() != null) {
            policy.setDescription(req.description().trim());
        }
        if (req.enabled() != null) {
            policy.setEnabled(req.enabled());
        }
        if (req.priority() != null) {
            policy.setPriority(req.priority());
        }
        policy.setValidFrom(req.validFrom());
        policy.setValidTo(req.validTo());
        if (req.rules() != null) {
            var validated = validateRules(req.rules(), req.overrideCriticalDomains());
            rules.deleteByPolicyId(id);
            saveRules(id, validated);
        }
        policy.bumpRevision();
        audit.record(AuditAction.WEB_POLICY_UPDATED, "WEB_POLICY", id, Map.of("revision", policy.getRevision()));
        realtime.publish("WEB_POLICY", envelope(id, "updated"));
        return toResponse(policy);
    }

    @Transactional
    public void delete(Long id) {
        WebPolicy policy = load(id);
        policies.delete(policy);   // rules + assignments cascade
        audit.record(AuditAction.WEB_POLICY_DELETED, "WEB_POLICY", id, Map.of("name", policy.getName()));
        realtime.publish("WEB_POLICY", envelope(id, "deleted"));
    }

    // ---------- Assignments ----------

    @Transactional(readOnly = true)
    public List<AssignmentResponse> listAssignments(Long policyId) {
        load(policyId);
        return assignments.findByPolicyId(policyId).stream().map(this::toAssignmentResponse).toList();
    }

    @Transactional
    public AssignmentResponse assign(Long policyId, TargetType type, Long targetId) {
        load(policyId);
        validateTarget(type, targetId);
        if (assignments.existsByPolicyIdAndTargetTypeAndTargetId(policyId, type.name(), targetId)) {
            throw ApiException.conflict("Policy is already assigned to that target");
        }
        var saved = assignments.save(new WebPolicyAssignment(policyId, type, targetId));
        audit.record(AuditAction.WEB_POLICY_ASSIGNED, "WEB_POLICY", policyId,
                Map.of("targetType", type.name(), "targetId", targetId));
        realtime.publish("WEB_POLICY", envelope(policyId, "assigned"));
        return toAssignmentResponse(saved);
    }

    @Transactional
    public void unassign(Long policyId, Long assignmentId) {
        var assignment = assignments.findById(assignmentId)
                .filter(a -> a.getPolicyId().equals(policyId))
                .orElseThrow(() -> ApiException.notFound("Assignment", assignmentId));
        assignments.delete(assignment);
        audit.record(AuditAction.WEB_POLICY_UNASSIGNED, "WEB_POLICY", policyId, Map.of("assignmentId", assignmentId));
        realtime.publish("WEB_POLICY", envelope(policyId, "unassigned"));
    }

    // ---------- Effective resolution (agent + event evaluation) ----------

    public record EffectiveRuleSet(List<EffectiveRule> rules, EffectiveWebPolicyResponse response) {
    }

    @Transactional(readOnly = true)
    public EffectiveRuleSet resolve(Device device) {
        Long employeeId = device.getEmployee() == null ? null : device.getEmployee().getId();
        List<WebPolicyAssignment> assigned = assignments.findForDevice(device.getId(), employeeId);
        Instant now = clock.instant();
        Set<Long> policyIds = assigned.stream().map(WebPolicyAssignment::getPolicyId).collect(Collectors.toSet());
        List<WebPolicy> active = policies.findAllById(policyIds).stream()
                .filter(p -> p.isActiveAt(now)).toList();
        if (active.isEmpty()) {
            return new EffectiveRuleSet(List.of(),
                    new EffectiveWebPolicyResponse("0", List.of(), List.of(), List.of(), now));
        }
        Map<Long, Integer> priority = active.stream().collect(Collectors.toMap(WebPolicy::getId, WebPolicy::getPriority));
        List<EffectiveRule> eff = new ArrayList<>();
        Set<String> candidates = new TreeSet<>();
        for (WebPolicyRule rule : rules.findByPolicyIdIn(priority.keySet())) {
            int prio = priority.get(rule.getPolicyId());
            if (rule.getDomain() != null) {
                String d = DomainMatcher.canonicalize(rule.getDomain());
                if (!d.isEmpty()) {
                    eff.add(new EffectiveRule(rule.getPolicyId(), rule.getId(), prio, d, rule.getAction()));
                    candidates.add(d);
                }
            }
            if (rule.getCategory() != null) {
                for (String d : WebCategories.domainsOf(rule.getCategory())) {
                    eff.add(new EffectiveRule(rule.getPolicyId(), rule.getId(), prio, d, rule.getAction()));
                    candidates.add(d);
                }
            }
        }
        List<String> block = new ArrayList<>();
        List<String> allow = new ArrayList<>();
        List<String> monitor = new ArrayList<>();
        for (String d : candidates) {
            switch (WebPolicyEvaluator.decide(eff, d).action()) {
                case BLOCK -> block.add(d);
                case ALLOW -> allow.add(d);
                case MONITOR -> monitor.add(d);
                case null -> { }
            }
        }
        return new EffectiveRuleSet(eff,
                new EffectiveWebPolicyResponse(revisionHash(block, allow), block, allow, monitor, now));
    }

    // ---------- Enforcement status ----------

    @Transactional
    public void reportEnforcement(Long deviceId, WebEnforcementReport report) {
        WebEnforcementStatus status = statuses.findById(deviceId).orElseGet(() -> new WebEnforcementStatus(deviceId));
        status.report(report.mechanism(), report.healthy(), report.appliedRevision(), report.browsers(),
                report.error(), clock.instant());
        statuses.save(status);
    }

    @Transactional(readOnly = true)
    public WebEnforcementStatusResponse enforcementStatus(Long deviceId) {
        Device device = devices.findById(deviceId).orElseThrow(() -> ApiException.notFound("Device", deviceId));
        String effectiveRevision = resolve(device).response().revision();
        var status = statuses.findById(deviceId).orElse(null);
        if (status == null) {
            return new WebEnforcementStatusResponse(deviceId, null, false, null, effectiveRevision, null,
                    null, null, "Device has not reported web enforcement yet", true);
        }
        boolean stale = status.getAppliedRevision() == null || !status.getAppliedRevision().equals(effectiveRevision);
        return new WebEnforcementStatusResponse(deviceId, status.getMechanism(), status.isHealthy(),
                status.getAppliedRevision(), effectiveRevision, status.getBrowsers(), status.getLastSyncAt(),
                status.getLastEventAt(), status.getLastError(), stale);
    }

    // ---------- helpers ----------

    private WebPolicy load(Long id) {
        return policies.findById(id).orElseThrow(() -> ApiException.notFound("Web policy", id));
    }

    private List<RuleInput> validateRules(List<RuleInput> input, boolean overrideCritical) {
        if (input == null || input.isEmpty()) {
            throw ApiException.badRequest("A policy must have at least one rule");
        }
        List<RuleInput> cleaned = new ArrayList<>();
        for (RuleInput r : input) {
            boolean hasDomain = r.domain() != null && !r.domain().isBlank();
            boolean hasCategory = r.category() != null && !r.category().isBlank();
            if (hasDomain == hasCategory) {
                throw ApiException.badRequest("Each rule needs exactly one of a domain or a category");
            }
            if (hasDomain) {
                if (!DomainMatcher.isValidRuleDomain(r.domain())) {
                    throw ApiException.badRequest("Invalid domain: " + r.domain());
                }
                String d = DomainMatcher.canonicalize(r.domain());
                if (r.action() == WebAction.BLOCK && !overrideCritical && CriticalDomains.isCritical(d)) {
                    throw ApiException.badRequest("Refusing to block essential domain '" + d
                            + "' without an explicit override");
                }
                cleaned.add(new RuleInput(d, null, r.action()));
            } else {
                if (!WebCategories.exists(r.category())) {
                    throw ApiException.badRequest("Unknown category: " + r.category());
                }
                if (r.action() == WebAction.BLOCK && !overrideCritical) {
                    for (String cd : WebCategories.domainsOf(r.category())) {
                        if (CriticalDomains.isCritical(cd)) {
                            throw ApiException.badRequest("Category '" + r.category()
                                    + "' includes an essential domain; use an override to block it");
                        }
                    }
                }
                cleaned.add(new RuleInput(null, r.category().trim().toUpperCase(), r.action()));
            }
        }
        return cleaned;
    }

    private void saveRules(Long policyId, List<RuleInput> cleaned) {
        rules.saveAll(cleaned.stream()
                .map(r -> new WebPolicyRule(policyId, r.domain(), r.category(), r.action()))
                .toList());
    }

    private void validateTarget(TargetType type, Long targetId) {
        boolean exists = type == TargetType.DEVICE ? devices.existsById(targetId) : employees.existsById(targetId);
        if (!exists) {
            throw ApiException.notFound(type.name().toLowerCase(), targetId);
        }
    }

    private WebPolicyResponse toResponse(WebPolicy p) {
        List<RuleResponse> ruleResponses = rules.findByPolicyId(p.getId()).stream()
                .map(RuleResponse::from).toList();
        long assignmentCount = assignments.findByPolicyId(p.getId()).size();
        return new WebPolicyResponse(p.getId(), p.getName(), p.getDescription(), p.isEnabled(), p.getPriority(),
                p.getValidFrom(), p.getValidTo(), p.getRevision(), ruleResponses, assignmentCount,
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private AssignmentResponse toAssignmentResponse(WebPolicyAssignment a) {
        String label = a.getTargetType() == TargetType.DEVICE
                ? devices.findById(a.getTargetId()).map(Device::getDeviceCode).orElse(null)
                : employees.findById(a.getTargetId()).map(Employee::getFullName).orElse(null);
        return new AssignmentResponse(a.getId(), a.getTargetType(), a.getTargetId(), label, a.getCreatedAt());
    }

    private Map<String, Object> envelope(Long policyId, String change) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("policyId", policyId);
        m.put("change", change);
        m.put("employeeId", null);   // policy changes are visible to all-seeing admins
        return m;
    }

    private static String revisionHash(List<String> block, List<String> allow) {
        if (block.isEmpty() && allow.isEmpty()) {
            return "0";
        }
        try {
            String material = String.join(",", block) + "|" + String.join(",", allow);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            return Integer.toHexString((String.join(",", block) + "|" + String.join(",", allow)).hashCode());
        }
    }
}
