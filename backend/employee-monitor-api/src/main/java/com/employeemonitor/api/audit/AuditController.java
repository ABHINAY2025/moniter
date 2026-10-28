package com.employeemonitor.api.audit;

import com.employeemonitor.api.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
@Tag(name = "Audit")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    public record AuditLogResponse(Long id, Long actorUserId, String actorUsername, String action,
                                   String targetType, String targetId, String outcome, String ipAddress,
                                   Map<String, Object> metadata, Instant timestamp) {
        static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(a.getId(), a.getActorUserId(), a.getActorUsername(), a.getAction(),
                    a.getTargetType(), a.getTargetId(), a.getOutcome(), a.getIpAddress(), a.getMetadata(),
                    a.getCreatedAt());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_READ')")
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Specification<AuditLog> spec = (root, q, cb) -> cb.conjunction();
        if (actorUserId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("actorUserId"), actorUserId));
        }
        if (action != null && !action.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), action));
        }
        if (targetType != null && !targetType.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("targetType"), targetType));
        }
        if (targetId != null && !targetId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("targetId"), targetId));
        }
        if (from != null) {
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), to));
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.of(repository.findAll(spec, pageable), AuditLogResponse::from);
    }
}
