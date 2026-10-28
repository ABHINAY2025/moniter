package com.employeemonitor.api.devices;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.RateLimiter;
import com.employeemonitor.api.common.RequestContext;
import com.employeemonitor.api.common.SecureTokens;
import com.employeemonitor.api.config.AppProperties;
import com.employeemonitor.api.devices.DeviceDtos.DeviceRegistrationResponse;
import com.employeemonitor.api.devices.DeviceDtos.RegisterDeviceRequest;
import com.employeemonitor.api.employees.EmployeeDtos.EmployeeSummary;
import com.employeemonitor.api.policies.MonitoringPolicy;
import com.employeemonitor.api.policies.PolicyDtos;
import com.employeemonitor.api.policies.PolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * First-run enrollment of an agent. The installer presents a company-issued enrollment token; on success the
 * device receives its own long-lived device token and its effective monitoring policy.
 */
@Service
public class DeviceRegistrationService {

    private final EnrollmentTokenRepository enrollmentTokens;
    private final DeviceRepository devices;
    private final DeviceTokenRepository deviceTokens;
    private final PolicyService policyService;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AppProperties.Security securityProps;
    private final AppProperties.Devices deviceProps;
    private final Clock clock;

    public DeviceRegistrationService(EnrollmentTokenRepository enrollmentTokens, DeviceRepository devices,
                                     DeviceTokenRepository deviceTokens, PolicyService policyService,
                                     AuditService audit, RateLimiter rateLimiter,
                                     AppProperties.Security securityProps, AppProperties.Devices deviceProps,
                                     Clock clock) {
        this.enrollmentTokens = enrollmentTokens;
        this.devices = devices;
        this.deviceTokens = deviceTokens;
        this.policyService = policyService;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.securityProps = securityProps;
        this.deviceProps = deviceProps;
        this.clock = clock;
    }

    @Transactional
    public DeviceRegistrationResponse register(RegisterDeviceRequest request) {
        rateLimiter.check("register:ip:" + RequestContext.clientIp(), securityProps.registerRateLimitPerMinute(),
                Duration.ofMinutes(1));
        Instant now = clock.instant();

        EnrollmentToken enrollment = enrollmentTokens
                .findByHashForUpdate(SecureTokens.sha256Hex(request.enrollmentToken().trim()))
                .filter(t -> t.isUsable(now))
                .orElseThrow(() -> {
                    audit.record(null, null, AuditAction.DEVICE_REGISTRATION_FAILED, "DEVICE", null, "FAILURE",
                            Map.of("hostname", request.hostname(), "reason", "INVALID_ENROLLMENT_TOKEN"));
                    return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_ENROLLMENT_TOKEN",
                            "Enrollment token is invalid, expired or already used");
                });
        enrollment.consume();

        // A reinstall on the same machine re-uses the existing device record and rotates its credentials.
        Optional<Device> existing = StringUtils.hasText(request.hardwareId())
                ? devices.findFirstByHardwareIdAndStatusNot(request.hardwareId(), Device.Status.RETIRED)
                : Optional.empty();
        boolean reEnrollment = existing.isPresent();
        Device device = existing.orElseGet(() ->
                new Device(String.format("DEV-%05d", devices.nextDeviceNumber()), request.hostname()));
        device.setHostname(request.hostname());
        device.setOsVersion(request.osVersion());
        device.setAgentVersion(request.agentVersion());
        device.setMacAddress(request.macAddress());
        device.setIpAddress(StringUtils.hasText(request.ipAddress()) ? request.ipAddress() : RequestContext.clientIp());
        device.setHardwareId(request.hardwareId());
        device.setStatus(Device.Status.ACTIVE);
        if (enrollment.getEmployee() != null) {
            device.setEmployee(enrollment.getEmployee());
        }
        device = devices.save(device);
        if (reEnrollment) {
            deviceTokens.revokeAllForDevice(device.getId(), now);
        }

        String rawToken = SecureTokens.generate(SecureTokens.DEVICE_PREFIX);
        Instant tokenExpires = deviceProps.tokenTtl() == null ? null : now.plus(deviceProps.tokenTtl());
        deviceTokens.save(new DeviceToken(device.getId(), SecureTokens.sha256Hex(rawToken), tokenExpires));

        MonitoringPolicy policy = policyService.effectivePolicy(device);

        Map<String, Object> meta = new HashMap<>();
        meta.put("hostname", device.getHostname());
        meta.put("deviceCode", device.getDeviceCode());
        meta.put("enrollmentTokenId", enrollment.getId());
        meta.put("reEnrollment", reEnrollment);
        audit.record(enrollment.getCreatedBy(), null, AuditAction.DEVICE_REGISTERED, "DEVICE", device.getId(),
                "SUCCESS", meta);

        return new DeviceRegistrationResponse(device.getId(), device.getDeviceCode(), rawToken, tokenExpires,
                EmployeeSummary.from(device.getEmployee()), PolicyDtos.revision(policy), policy.getSettings());
    }
}
