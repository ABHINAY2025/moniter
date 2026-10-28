package com.employeemonitor.api.alerts;

import com.employeemonitor.api.alerts.AlertDtos.AlertResponse;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.devices.Device;
import com.employeemonitor.api.devices.DeviceRepository;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.policies.PolicyService;
import com.employeemonitor.api.policies.PolicySettings;
import com.employeemonitor.api.presence.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Raises, stores and publishes suspicious-activity alerts. Rules call {@link #raise} / {@link #raiseOfflineDuringWork};
 * alerts are deduplicated within a short window, persisted, and pushed live to admins over the same em:events channel
 * the presence feed uses (so the existing per-employee WebSocket filtering applies unchanged).
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    public static final String USB_CONNECTED = "USB_CONNECTED";
    public static final String FLAGGED_WEBSITE = "FLAGGED_WEBSITE";
    public static final String OFFLINE_DURING_WORK = "OFFLINE_DURING_WORK";

    private final AlertRepository alerts;
    private final DeviceRepository devices;
    private final EmployeeRepository employees;
    private final PolicyService policies;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Set<String> flaggedDomains;
    private final Duration dedupWindow;

    public AlertService(AlertRepository alerts, DeviceRepository devices, EmployeeRepository employees,
                        PolicyService policies, StringRedisTemplate redis, ObjectMapper objectMapper, Clock clock,
                        @Value("${app.alerts.flagged-domains:youtube.com,netflix.com,primevideo.com,hotstar.com,"
                                + "facebook.com,instagram.com,tiktok.com,twitch.tv,thepiratebay.org,1337x.to}")
                        List<String> flaggedDomains,
                        @Value("${app.alerts.dedup-minutes:10}") long dedupMinutes) {
        this.alerts = alerts;
        this.devices = devices;
        this.employees = employees;
        this.policies = policies;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.flaggedDomains = flaggedDomains.stream()
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.dedupWindow = Duration.ofMinutes(Math.max(1, dedupMinutes));
    }

    public boolean isFlaggedDomain(String domain) {
        return domain != null && flaggedDomains.contains(domain.trim().toLowerCase());
    }

    /** Raises an alert unless an identical one for this device was raised within the dedup window. */
    public void raise(String category, String severity, String message, Long deviceId, Long employeeId,
                      String dedupKey) {
        try {
            Instant now = clock.instant();
            if (dedupKey != null && deviceId != null && alerts
                    .existsByDeviceIdAndCategoryAndDedupKeyAndCreatedAtAfter(deviceId, category, dedupKey,
                            now.minus(dedupWindow))) {
                return;
            }
            String deviceCode = deviceId == null ? null
                    : devices.findById(deviceId).map(Device::getDeviceCode).orElse(null);
            String employeeName = employeeId == null ? null
                    : employees.findById(employeeId).map(Employee::getFullName).orElse(null);
            Alert saved = alerts.save(new Alert(category, severity, message, deviceId, employeeId, deviceCode,
                    employeeName, dedupKey, now));
            publish(saved);
        } catch (RuntimeException e) {
            log.warn("Failed to raise {} alert: {}", category, e.getMessage());
        }
    }

    /** Raises an OFFLINE_DURING_WORK alert only when the device is inside its policy's work-hours window. */
    public void raiseOfflineDuringWork(Long deviceId, Long employeeId) {
        if (deviceId == null) {
            return;
        }
        try {
            Device device = devices.findById(deviceId).orElse(null);
            if (device == null) {
                return;
            }
            PolicySettings settings = policies.effectivePolicy(device).getSettings();
            if (!WorkHours.isWithin(settings, clock.instant())) {
                return;
            }
            String label = device.getHostname() != null ? device.getHostname() : device.getDeviceCode();
            raise(OFFLINE_DURING_WORK, "WARNING", label + " went offline during work hours", deviceId, employeeId,
                    "offline");
        } catch (RuntimeException e) {
            log.warn("Failed to evaluate offline alert for device {}: {}", deviceId, e.getMessage());
        }
    }

    private void publish(Alert a) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", a.getId());
            payload.put("category", a.getCategory());
            payload.put("severity", a.getSeverity());
            payload.put("message", a.getMessage());
            payload.put("deviceId", a.getDeviceId());
            payload.put("employeeId", a.getEmployeeId());
            payload.put("deviceCode", a.getDeviceCode());
            payload.put("employeeName", a.getEmployeeName());
            payload.put("createdAt", a.getCreatedAt());
            payload.put("acknowledged", false);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "ALERT");
            envelope.put("payload", payload);
            redis.convertAndSend(PresenceService.EVENTS_CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("Failed to publish alert {}: {}", a.getId(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertResponse> list(boolean unacknowledgedOnly, Pageable pageable) {
        Page<Alert> page = unacknowledgedOnly ? alerts.findByAcknowledgedAtIsNull(pageable) : alerts.findAll(pageable);
        return PageResponse.of(page, AlertResponse::from);
    }

    @Transactional
    public AlertResponse acknowledge(Long id, Long userId) {
        Alert alert = alerts.findById(id).orElseThrow(() -> ApiException.notFound("Alert", id));
        if (alert.getAcknowledgedAt() == null) {
            alert.acknowledge(userId, clock.instant());
        }
        return AlertResponse.from(alert);
    }
}
