package com.employeemonitor.api.policies;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * Server-controlled monitoring policy, delivered to agents as JSON. Each monitoring category can be
 * switched off independently; agents must honour these flags and the work-hours window.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolicySettings(
        @NotNull Boolean sessionMonitoring,
        @NotNull @Min(60) @Max(3600) Integer idleThresholdSeconds,
        @NotNull Boolean applicationMonitoring,
        @NotNull Boolean websiteMonitoring,
        @NotNull Boolean usbMonitoring,
        @NotNull Boolean networkMonitoring,
        @NotNull Boolean zscalerMonitoring,
        @NotNull Boolean screenshots,
        @NotNull Integer screenshotIntervalSeconds,
        @NotNull Boolean liveView,
        @NotNull @Min(1) @Max(240) Integer liveViewMaxMinutes,
        @NotNull @Min(10) @Max(300) Integer heartbeatIntervalSeconds,
        @NotNull @Min(60) @Max(3600) Integer policySyncIntervalSeconds,
        @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "must be HH:mm") String monitoringStart,
        @NotBlank @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "must be HH:mm") String monitoringEnd,
        @NotEmpty Set<DayOfWeek> workDays,
        @NotBlank String timezone,
        @NotNull @Min(1) @Max(3650) Integer retentionDays,
        @NotNull @Min(1) @Max(365) Integer screenshotRetentionDays,
        @NotNull Boolean requireControlConsent) {

    public static final Set<Integer> ALLOWED_SCREENSHOT_INTERVALS = Set.of(300, 600, 900, 1800);

    /**
     * Older stored/received policies predate {@code requireControlConsent}; treat a missing value as the safe
     * default (consent required). Admins opt a device into unattended control by setting it to {@code false}.
     */
    public PolicySettings {
        if (requireControlConsent == null) {
            requireControlConsent = true;
        }
    }

    @AssertTrue(message = "screenshotIntervalSeconds must be one of 300, 600, 900, 1800")
    @JsonIgnore
    public boolean isScreenshotIntervalAllowed() {
        return screenshotIntervalSeconds == null || ALLOWED_SCREENSHOT_INTERVALS.contains(screenshotIntervalSeconds);
    }

    @AssertTrue(message = "timezone must be a valid IANA zone id, e.g. Asia/Kolkata")
    @JsonIgnore
    public boolean isTimezoneValid() {
        if (timezone == null) {
            return true;
        }
        try {
            ZoneId.of(timezone);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    @AssertTrue(message = "monitoringEnd must be after monitoringStart")
    @JsonIgnore
    public boolean isWindowValid() {
        try {
            return monitoringStart == null || monitoringEnd == null
                    || LocalTime.parse(monitoringEnd).isAfter(LocalTime.parse(monitoringStart));
        } catch (DateTimeException e) {
            return true; // reported by @Pattern
        }
    }
}
