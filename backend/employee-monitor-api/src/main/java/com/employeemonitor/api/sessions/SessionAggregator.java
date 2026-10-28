package com.employeemonitor.api.sessions;

import com.employeemonitor.api.sessions.SessionEventType.PresenceState;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Derives per-day durations by walking the ordered event stream. Pure and side-effect free so it can be
 * unit-tested without a database. The window is [dayStart, dayEnd); the still-open final interval is clamped
 * to min(dayEnd, now).
 */
public final class SessionAggregator {

    public record Summary(Instant sessionStart, Instant sessionEnd, long sessionSeconds,
                          long activeSeconds, long idleSeconds, long lockedSeconds, boolean currentlyOnline) {
    }

    private SessionAggregator() {
    }

    /**
     * @param initialState state entering the window (derived from the last event before dayStart), or OFFLINE
     * @param events       events within [dayStart, dayEnd), ordered by time
     */
    public static Summary summarize(PresenceState initialState, List<SessionEvent> events,
                                    Instant dayStart, Instant dayEnd, Instant now) {
        Map<PresenceState, Long> seconds = new EnumMap<>(PresenceState.class);
        for (PresenceState s : PresenceState.values()) {
            seconds.put(s, 0L);
        }
        Instant windowEnd = now.isBefore(dayEnd) ? now : dayEnd;

        PresenceState state = initialState;
        Instant segmentStart = dayStart;
        // Already online at midnight: the session is in progress from the start of the day.
        Instant sessionStart = initialState != PresenceState.OFFLINE ? dayStart : null;
        Instant sessionEnd = null;

        for (SessionEvent event : events) {
            Instant t = clamp(event.getOccurredAt(), dayStart, windowEnd);
            accumulate(seconds, state, segmentStart, t);

            PresenceState next = event.getEventType().resultingState();
            if (next != null) {
                if (next != PresenceState.OFFLINE) {
                    if (sessionStart == null) {
                        sessionStart = t;
                    }
                    sessionEnd = t;
                } else {
                    sessionEnd = t;
                }
                state = next;
            }
            segmentStart = t;
        }
        accumulate(seconds, state, segmentStart, windowEnd);

        boolean online = state != PresenceState.OFFLINE;
        if (online && sessionStart == null) {
            // Carried into the day already online with no in-day transition.
            sessionStart = dayStart;
        }
        if (online) {
            sessionEnd = windowEnd;
        }
        long active = seconds.get(PresenceState.ACTIVE);
        long idle = seconds.get(PresenceState.IDLE);
        long locked = seconds.get(PresenceState.LOCKED);
        long session = sessionStart == null ? 0
                : Math.max(0, Duration.between(sessionStart, sessionEnd).getSeconds());
        return new Summary(sessionStart, sessionEnd, session, active, idle, locked, online);
    }

    private static void accumulate(Map<PresenceState, Long> seconds, PresenceState state, Instant from, Instant to) {
        if (to.isAfter(from)) {
            seconds.merge(state, Duration.between(from, to).getSeconds(), Long::sum);
        }
    }

    private static Instant clamp(Instant t, Instant min, Instant max) {
        if (t.isBefore(min)) {
            return min;
        }
        return t.isAfter(max) ? max : t;
    }
}
