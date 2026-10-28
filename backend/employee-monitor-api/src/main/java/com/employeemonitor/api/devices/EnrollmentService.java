package com.employeemonitor.api.devices;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.common.SecureTokens;
import com.employeemonitor.api.devices.DeviceDtos.CreateEnrollmentTokenRequest;
import com.employeemonitor.api.devices.DeviceDtos.EnrollmentTokenCreatedResponse;
import com.employeemonitor.api.devices.DeviceDtos.EnrollmentTokenResponse;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeSummary;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class EnrollmentService {

    private final EnrollmentTokenRepository tokens;
    private final EmployeeRepository employees;
    private final AuditService audit;
    private final Clock clock;

    public EnrollmentService(EnrollmentTokenRepository tokens, EmployeeRepository employees, AuditService audit,
                             Clock clock) {
        this.tokens = tokens;
        this.employees = employees;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public EnrollmentTokenCreatedResponse create(CreateEnrollmentTokenRequest request) {
        Employee employee = null;
        if (request.employeeId() != null) {
            employee = employees.findById(request.employeeId())
                    .orElseThrow(() -> ApiException.notFound("Employee", request.employeeId()));
        }
        int maxUses = request.maxUses() == null ? 1 : request.maxUses();
        int hours = request.expiresInHours() == null ? 72 : request.expiresInHours();
        Instant expiresAt = clock.instant().plus(Duration.ofHours(hours));
        String raw = SecureTokens.generate(SecureTokens.ENROLLMENT_PREFIX);

        EnrollmentToken token = tokens.save(new EnrollmentToken(SecureTokens.sha256Hex(raw), request.description(),
                employee, maxUses, expiresAt, CurrentUser.require().id()));

        Map<String, Object> meta = new HashMap<>();
        meta.put("maxUses", maxUses);
        meta.put("expiresAt", expiresAt.toString());
        if (employee != null) {
            meta.put("employeeId", employee.getId());
        }
        audit.record(AuditAction.ENROLLMENT_TOKEN_CREATED, "ENROLLMENT_TOKEN", token.getId(), meta);
        return new EnrollmentTokenCreatedResponse(token.getId(), raw, token.getDescription(),
                EmployeeSummary.from(employee), maxUses, expiresAt);
    }

    @Transactional(readOnly = true)
    public PageResponse<EnrollmentTokenResponse> list(int page, int size) {
        Instant now = clock.instant();
        return PageResponse.of(tokens.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size)),
                t -> EnrollmentTokenResponse.from(t, now));
    }

    @Transactional
    public void revoke(Long id) {
        EnrollmentToken token = tokens.findById(id).orElseThrow(() -> ApiException.notFound("Enrollment token", id));
        token.revoke(clock.instant());
        audit.record(AuditAction.ENROLLMENT_TOKEN_REVOKED, "ENROLLMENT_TOKEN", id, null);
    }
}
