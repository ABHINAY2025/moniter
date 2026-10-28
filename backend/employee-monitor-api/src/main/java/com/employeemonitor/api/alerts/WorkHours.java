package com.employeemonitor.api.alerts;

import com.employeemonitor.api.policies.PolicySettings;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Decides whether a given instant falls inside a policy's monitoring window (work day + hours in its timezone). */
public final class WorkHours {

    private WorkHours() {
    }

    public static boolean isWithin(PolicySettings settings, Instant now) {
        if (settings == null) {
            return true;
        }
        try {
            ZonedDateTime local = now.atZone(ZoneId.of(settings.timezone()));
            if (settings.workDays() != null && !settings.workDays().contains(local.getDayOfWeek())) {
                return false;
            }
            LocalTime start = LocalTime.parse(settings.monitoringStart());
            LocalTime end = LocalTime.parse(settings.monitoringEnd());
            LocalTime t = local.toLocalTime();
            return !t.isBefore(start) && !t.isAfter(end);
        } catch (RuntimeException e) {
            // Misconfigured policy: don't suppress the alert.
            return true;
        }
    }
}
