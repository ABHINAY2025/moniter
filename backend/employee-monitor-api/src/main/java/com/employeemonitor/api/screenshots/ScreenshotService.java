package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.config.AppProperties;
import com.employeemonitor.api.employees.EmployeeAccessService;
import com.employeemonitor.api.policies.PolicyService;
import com.employeemonitor.api.screenshots.ScreenshotDtos.ScreenshotContent;
import com.employeemonitor.api.screenshots.ScreenshotDtos.ScreenshotMetadata;
import com.employeemonitor.api.screenshots.ScreenshotDtos.UploadRequest;
import com.employeemonitor.api.screenshots.ScreenshotDtos.UploadResponse;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.DevicePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ScreenshotService {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotService.class);

    private final ScreenshotRepository repository;
    private final ScreenshotStorage storage;
    private final EmployeeAccessService access;
    private final PolicyService policyService;
    private final AuditService audit;
    private final AppProperties.Screenshots props;
    private final Clock clock;

    public ScreenshotService(ScreenshotRepository repository, ScreenshotStorage storage, EmployeeAccessService access,
                             PolicyService policyService, AuditService audit, AppProperties.Screenshots props,
                             Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.access = access;
        this.policyService = policyService;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public UploadResponse ingest(DevicePrincipal device, UploadRequest request) {
        var existing = repository.findByDeviceIdAndClientEventId(device.deviceId(), request.clientEventId());
        if (existing.isPresent()) {
            return new UploadResponse(existing.get().getId(), true);
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.imageBase64());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("imageBase64 is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > props.maxBytes()) {
            throw ApiException.badRequest("Screenshot size must be between 1 and " + props.maxBytes() + " bytes");
        }

        String ext = request.contentType().endsWith("png") ? ".png" : ".jpg";
        String storageKey = UUID.randomUUID().toString().replace("-", "") + ext;
        storage.put(storageKey, bytes);

        Screenshot screenshot = new Screenshot(request.clientEventId(), device.deviceId(), device.employeeId(),
                request.capturedAt(), storageKey, request.contentType(), request.width(), request.height(),
                bytes.length, storage.encryptsAtRest());
        try {
            screenshot = repository.save(screenshot);
        } catch (RuntimeException e) {
            // Metadata insert failed (e.g. concurrent duplicate): don't leak the orphan blob.
            storage.delete(storageKey);
            throw e;
        }
        return new UploadResponse(screenshot.getId(), false);
    }

    @Transactional(readOnly = true)
    public PageResponse<ScreenshotMetadata> list(AuthenticatedUser user, Long employeeId, LocalDate date, ZoneId zone,
                                                 int page, int size) {
        access.requireRead(user, employeeId);
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        return PageResponse.of(repository.findByEmployeeIdAndCapturedAtBetweenOrderByCapturedAtDesc(
                employeeId, start, end, PageRequest.of(page, size)), ScreenshotMetadata::from);
    }

    /** Authorizes, audits and returns the image bytes. Images are never served without passing through here. */
    @Transactional
    public ScreenshotContent view(AuthenticatedUser user, Long screenshotId) {
        Screenshot screenshot = repository.findById(screenshotId)
                .orElseThrow(() -> ApiException.notFound("Screenshot", screenshotId));
        // 404 (not 403) when the caller may not see this employee, so ids cannot be probed.
        if (!access.canRead(user, screenshot.getEmployeeId())) {
            throw ApiException.notFound("Screenshot", screenshotId);
        }
        byte[] bytes = storage.get(screenshot.getStorageKey());
        audit.record(AuditAction.SCREENSHOT_VIEWED, "SCREENSHOT", screenshotId,
                Map.of("employeeId", String.valueOf(screenshot.getEmployeeId()),
                        "capturedAt", screenshot.getCapturedAt().toString()));
        return new ScreenshotContent(bytes, screenshot.getContentType());
    }

    /** Deletes screenshots (bytes + metadata) older than the default policy's screenshot retention. */
    @Scheduled(cron = "${app.screenshots.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int retentionDays;
        try {
            retentionDays = policyService.defaultPolicy().getSettings().screenshotRetentionDays();
        } catch (RuntimeException e) {
            log.warn("Screenshot purge skipped: {}", e.getMessage());
            return;
        }
        Instant cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        List<Screenshot> expired = repository.findExpired(cutoff, PageRequest.of(0, 1000));
        if (expired.isEmpty()) {
            return;
        }
        for (Screenshot s : expired) {
            storage.delete(s.getStorageKey());
        }
        repository.deleteAll(expired);
        audit.record(null, "system", AuditAction.SCREENSHOTS_PURGED, "SCREENSHOT", null, "SUCCESS",
                Map.of("deleted", expired.size(), "olderThanDays", retentionDays));
        log.info("Purged {} screenshots older than {} days", expired.size(), retentionDays);
    }

    public static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("Invalid timezone: " + tz);
        }
    }
}
