package com.employeemonitor.api.liveview;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.common.RequestContext;
import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeSummary;
import com.employeemonitor.api.employees.EmployeeAccessService;
import com.employeemonitor.api.employees.ManagerAssignment;
import com.employeemonitor.api.employees.ManagerAssignmentRepository;
import com.employeemonitor.api.liveview.LiveViewDtos.SessionResponse;
import com.employeemonitor.api.liveview.LiveViewDtos.SessionSummary;
import com.employeemonitor.api.policies.MonitoringPolicy;
import com.employeemonitor.api.policies.PolicyService;
import com.employeemonitor.api.presence.PresenceService;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.users.Permissions;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class LiveViewService {

    private final LiveViewSessionRepository sessions;
    private final DeviceRepository devices;
    private final PolicyService policyService;
    private final PresenceService presence;
    private final EmployeeAccessService access;
    private final ManagerAssignmentRepository assignments;
    private final AuditService audit;
    private final Clock clock;

    public LiveViewService(LiveViewSessionRepository sessions, DeviceRepository devices, PolicyService policyService,
                           PresenceService presence, EmployeeAccessService access,
                           ManagerAssignmentRepository assignments, AuditService audit, Clock clock) {
        this.sessions = sessions;
        this.devices = devices;
        this.policyService = policyService;
        this.presence = presence;
        this.access = access;
        this.assignments = assignments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public SessionResponse create(AuthenticatedUser user, Long deviceId) {
        Device device = devices.findById(deviceId).orElseThrow(() -> ApiException.notFound("Device", deviceId));
        authorize(user, device);

        if (sessions.existsByDeviceIdAndStatusNot(deviceId, LiveViewSession.Status.ENDED)) {
            throw new ApiException(HttpStatus.CONFLICT, "LIVE_VIEW_IN_PROGRESS",
                    "A live-view session for this device is already in progress");
        }

        MonitoringPolicy policy = policyService.effectivePolicy(device);
        Long employeeId = device.getEmployee() == null ? null : device.getEmployee().getId();
        LiveViewSession session = new LiveViewSession(UUID.randomUUID(), user.id(), user.username(), deviceId,
                employeeId, RequestContext.clientIp());
        sessions.save(session);

        Map<String, Object> meta = new HashMap<>();
        meta.put("deviceId", deviceId);
        meta.put("employeeId", employeeId);
        meta.put("sessionId", session.getSessionId().toString());
        audit.record(AuditAction.LIVE_VIEW_STARTED, "LIVE_VIEW", session.getSessionId(), meta);

        return new SessionResponse(session.getSessionId(), deviceId, device.getDeviceCode(),
                EmployeeSummary.from(device.getEmployee()), session.getStatus().name(), session.getStartedAt(),
                policy.getSettings().liveViewMaxMinutes() * 60, "/ws/live-view");
    }

    private void authorize(AuthenticatedUser user, Device device) {
        Long employeeId = device.getEmployee() == null ? null : device.getEmployee().getId();
        if (!access.canRead(user, employeeId)) {
            throw ApiException.notFound("Device", device.getId());
        }
        // Managers (and any scoped role) may only watch employees explicitly flagged for live view.
        if (access.isScoped(user)) {
            boolean allowed = employeeId != null
                    && assignments.findById(new ManagerAssignment.Key(user.id(), employeeId))
                    .map(ManagerAssignment::isLiveViewAllowed).orElse(false);
            if (!allowed) {
                throw ApiException.forbidden("Live view is not authorized for this employee");
            }
        }
        if (!policyService.effectivePolicy(device).getSettings().liveView()) {
            throw ApiException.forbidden("Live view is disabled by policy for this device");
        }
        if (device.getStatus() != Device.Status.ACTIVE) {
            throw ApiException.badRequest("Device is not active");
        }
        if (presence.get(device.getId()).map(p -> !p.online()).orElse(true)) {
            throw new ApiException(HttpStatus.CONFLICT, "DEVICE_OFFLINE", "Device is offline");
        }
    }

    @Transactional(readOnly = true)
    public int maxDurationSeconds(Long deviceId) {
        return devices.findById(deviceId)
                .map(d -> policyService.effectivePolicy(d).getSettings().liveViewMaxMinutes() * 60)
                .orElse(1800);
    }

    @Transactional
    public void stopByAdmin(AuthenticatedUser user, UUID sessionId) {
        LiveViewSession session = sessions.findBySessionId(sessionId)
                .orElseThrow(() -> ApiException.notFound("Live-view session", sessionId));
        boolean owner = session.getAdminUserId() != null && session.getAdminUserId().equals(user.id());
        if (!owner && !user.has(Permissions.EMPLOYEE_READ_ALL)) {
            throw ApiException.forbidden("Only the initiating admin may stop this session");
        }
        end(session, "STOPPED_BY_ADMIN");
    }

    @Transactional
    public void endBySystem(UUID sessionId, String reason) {
        sessions.findBySessionId(sessionId).ifPresent(s -> end(s, reason));
    }

    @Transactional
    public void markActive(UUID sessionId) {
        sessions.findBySessionId(sessionId).ifPresent(s -> s.markActive(clock.instant()));
    }

    private void end(LiveViewSession session, String reason) {
        if (session.isEnded()) {
            return;
        }
        session.end(clock.instant(), reason);
        Map<String, Object> meta = new HashMap<>();
        meta.put("sessionId", session.getSessionId().toString());
        meta.put("deviceId", session.getDeviceId());
        meta.put("employeeId", session.getEmployeeId());
        meta.put("durationSeconds", session.getDurationSeconds());
        meta.put("reason", reason);
        audit.record(session.getAdminUserId(), session.getAdminUsername(), AuditAction.LIVE_VIEW_STOPPED,
                "LIVE_VIEW", session.getSessionId(), "SUCCESS", meta);
    }

    @Transactional(readOnly = true)
    public Optional<LiveViewSession> activeForDevice(Long deviceId) {
        return sessions.findByDeviceIdAndStatusNot(deviceId, LiveViewSession.Status.ENDED);
    }

    @Transactional(readOnly = true)
    public PageResponse<SessionSummary> history(int page, int size) {
        return PageResponse.of(sessions.findAllByOrderByStartedAtDesc(PageRequest.of(page, size)),
                SessionSummary::from);
    }

    /** Sessions exceeding the device's configured maximum duration, to be force-stopped by the signaling layer. */
    @Transactional(readOnly = true)
    public List<UUID> findTimedOut() {
        Instant now = clock.instant();
        return sessions.findByStatusNot(LiveViewSession.Status.ENDED).stream()
                .filter(s -> {
                    int maxMinutes = devices.findById(s.getDeviceId())
                            .map(d -> policyService.effectivePolicy(d).getSettings().liveViewMaxMinutes())
                            .orElse(30);
                    return s.getStartedAt().plus(Duration.ofMinutes(maxMinutes)).isBefore(now);
                })
                .map(LiveViewSession::getSessionId)
                .toList();
    }
}
