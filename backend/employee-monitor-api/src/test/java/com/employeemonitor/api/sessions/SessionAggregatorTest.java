package com.employeemonitor.api.sessions;

import com.employeemonitor.api.sessions.SessionEventType.PresenceState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAggregatorTest {

    private static final Instant DAY_START = Instant.parse("2026-10-06T00:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-10-07T00:00:00Z");

    private static SessionEvent ev(SessionEventType type, String time) {
        return new SessionEvent(UUID.randomUUID(), 1L, 2L, type, Instant.parse(time));
    }

    @Test
    void accumulatesActiveIdleLockedAcrossTransitions() {
        // 09:00 login, 10:00 idle, 10:30 active, 12:00 lock, 12:30 unlock, 13:00 logout
        List<SessionEvent> events = List.of(
                ev(SessionEventType.LOGIN, "2026-10-06T09:00:00Z"),
                ev(SessionEventType.IDLE, "2026-10-06T10:00:00Z"),
                ev(SessionEventType.ACTIVE, "2026-10-06T10:30:00Z"),
                ev(SessionEventType.LOCK, "2026-10-06T12:00:00Z"),
                ev(SessionEventType.UNLOCK, "2026-10-06T12:30:00Z"),
                ev(SessionEventType.LOGOUT, "2026-10-06T13:00:00Z"));
        Instant now = Instant.parse("2026-10-06T20:00:00Z");

        SessionAggregator.Summary s = SessionAggregator.summarize(PresenceState.OFFLINE, events, DAY_START, DAY_END, now);

        // active: 09-10 (3600) + 10:30-12:00 (5400) + 12:30-13:00 (1800) = 10800
        assertThat(s.activeSeconds()).isEqualTo(10800);
        assertThat(s.idleSeconds()).isEqualTo(1800);   // 10:00-10:30
        assertThat(s.lockedSeconds()).isEqualTo(1800); // 12:00-12:30
        assertThat(s.sessionStart()).isEqualTo(Instant.parse("2026-10-06T09:00:00Z"));
        assertThat(s.sessionEnd()).isEqualTo(Instant.parse("2026-10-06T13:00:00Z"));
        assertThat(s.sessionSeconds()).isEqualTo(4 * 3600);
        assertThat(s.currentlyOnline()).isFalse();
    }

    @Test
    void openSessionIsClampedToNow() {
        List<SessionEvent> events = List.of(ev(SessionEventType.LOGIN, "2026-10-06T09:00:00Z"));
        Instant now = Instant.parse("2026-10-06T09:45:00Z");

        SessionAggregator.Summary s = SessionAggregator.summarize(PresenceState.OFFLINE, events, DAY_START, DAY_END, now);

        assertThat(s.activeSeconds()).isEqualTo(2700);
        assertThat(s.currentlyOnline()).isTrue();
        assertThat(s.sessionEnd()).isEqualTo(now);
    }

    @Test
    void stateCarriedInFromPreviousDayCountsFromMidnight() {
        // Logged in yesterday, first event today is a lock at 08:00.
        List<SessionEvent> events = List.of(ev(SessionEventType.LOCK, "2026-10-06T08:00:00Z"));
        Instant now = Instant.parse("2026-10-06T23:59:00Z");

        SessionAggregator.Summary s = SessionAggregator.summarize(PresenceState.ACTIVE, events, DAY_START, DAY_END, now);

        assertThat(s.activeSeconds()).isEqualTo(8 * 3600); // midnight -> 08:00
        assertThat(s.sessionStart()).isEqualTo(DAY_START);
    }

    @Test
    void noEventsAndOfflineCarryInYieldsZero() {
        SessionAggregator.Summary s = SessionAggregator.summarize(PresenceState.OFFLINE, List.of(),
                DAY_START, DAY_END, Instant.parse("2026-10-06T12:00:00Z"));
        assertThat(s.sessionSeconds()).isZero();
        assertThat(s.activeSeconds()).isZero();
        assertThat(s.sessionStart()).isNull();
        assertThat(s.currentlyOnline()).isFalse();
    }

    @Test
    void eventsOutsideWindowAreClamped() {
        // An event stamped before midnight is clamped to day start.
        List<SessionEvent> events = List.of(
                ev(SessionEventType.LOGIN, "2026-10-05T23:50:00Z"),
                ev(SessionEventType.IDLE, "2026-10-06T01:00:00Z"));
        Instant now = Instant.parse("2026-10-06T02:00:00Z");

        SessionAggregator.Summary s = SessionAggregator.summarize(PresenceState.OFFLINE, events, DAY_START, DAY_END, now);

        assertThat(s.activeSeconds()).isEqualTo(3600); // midnight -> 01:00
        assertThat(s.idleSeconds()).isEqualTo(3600);   // 01:00 -> now 02:00
    }
}
