package com.employeemonitor.api.sessions;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.employees.EmployeeAccessService;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.security.DevicePrincipal;
import com.employeemonitor.api.sessions.SessionDtos.IngestRequest;
import com.employeemonitor.api.sessions.SessionDtos.IngestResponse;
import com.employeemonitor.api.sessions.SessionDtos.SessionEventInput;
import com.employeemonitor.api.sessions.SessionDtos.SessionSummaryResponse;
import com.employeemonitor.api.sessions.SessionDtos.TimelineEntry;
import com.employeemonitor.api.sessions.SessionDtos.TimelineResponse;
import com.employeemonitor.api.sessions.SessionEventType.PresenceState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final SessionEventRepository events;
    private final EmployeeAccessService access;
    private final Clock clock;

    public SessionService(SessionEventRepository events, EmployeeAccessService access, Clock clock) {
        this.events = events;
        this.access = access;
        this.clock = clock;
    }

    /** Idempotent batch ingest from the agent. Events already stored (same device + clientEventId) are skipped. */
    @Transactional
    public IngestResponse ingest(DevicePrincipal device, IngestRequest request) {
        Instant now = clock.instant();
        Instant maxFuture = now.plus(Duration.ofMinutes(5));
        Instant minPast = now.minus(Duration.ofDays(7));

        List<SessionEventInput> valid = new ArrayList<>();
        Set<UUID> batchIds = new HashSet<>();
        int rejected = 0;
        for (SessionEventInput e : request.events()) {
            // Drop clock-skew outliers and duplicate ids within the same batch.
            if (e.occurredAt().isAfter(maxFuture) || e.occurredAt().isBefore(minPast) || !batchIds.add(e.clientEventId())) {
                rejected++;
            } else {
                valid.add(e);
            }
        }
        if (valid.isEmpty()) {
            return new IngestResponse(0, 0, rejected);
        }

        Set<UUID> existing = events.findExistingClientIds(device.deviceId(),
                valid.stream().map(SessionEventInput::clientEventId).toList());
        List<SessionEvent> toSave = new ArrayList<>();
        for (SessionEventInput e : valid) {
            if (!existing.contains(e.clientEventId())) {
                toSave.add(new SessionEvent(e.clientEventId(), device.deviceId(), device.employeeId(),
                        e.type(), e.occurredAt()));
            }
        }
        events.saveAll(toSave);
        log.debug("Ingested {} session events for device {} ({} dup, {} rejected)",
                toSave.size(), device.deviceCode(), valid.size() - toSave.size(), rejected);
        return new IngestResponse(toSave.size(), valid.size() - toSave.size(), rejected);
    }

    @Transactional(readOnly = true)
    public TimelineResponse timeline(AuthenticatedUser user, Long employeeId, LocalDate date, ZoneId zone) {
        access.requireRead(user, employeeId);
        Window w = windowFor(date, zone);
        List<TimelineEntry> entries = events
                .findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(employeeId, w.start(), w.end())
                .stream().map(TimelineEntry::from).toList();
        return new TimelineResponse(employeeId, date.toString(), zone.getId(), entries);
    }

    @Transactional(readOnly = true)
    public SessionSummaryResponse summary(AuthenticatedUser user, Long employeeId, LocalDate date, ZoneId zone) {
        access.requireRead(user, employeeId);
        Window w = windowFor(date, zone);
        List<SessionEvent> dayEvents =
                events.findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(employeeId, w.start(), w.end());
        SessionEvent before =
                events.findFirstByEmployeeIdAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(employeeId, w.start());
        PresenceState initial = carryInState(before);

        SessionAggregator.Summary s = SessionAggregator.summarize(initial, dayEvents, w.start(), w.end(), clock.instant());
        return new SessionSummaryResponse(employeeId, date.toString(), zone.getId(), s.sessionStart(), s.sessionEnd(),
                s.sessionSeconds(), s.activeSeconds(), s.idleSeconds(), s.lockedSeconds(), s.currentlyOnline());
    }

    private static PresenceState carryInState(SessionEvent before) {
        if (before == null) {
            return PresenceState.OFFLINE;
        }
        PresenceState s = before.getEventType().resultingState();
        return s == null ? PresenceState.OFFLINE : s;
    }

    private record Window(Instant start, Instant end) {
    }

    private Window windowFor(LocalDate date, ZoneId zone) {
        if (date == null) {
            throw ApiException.badRequest("date is required (YYYY-MM-DD)");
        }
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        return new Window(start, end);
    }
}
