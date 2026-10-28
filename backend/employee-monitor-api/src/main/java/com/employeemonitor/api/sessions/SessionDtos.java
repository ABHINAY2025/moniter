package com.employeemonitor.api.sessions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SessionDtos {

    private SessionDtos() {
    }

    // ---------- Agent ingest ----------

    public record SessionEventInput(
            @NotNull UUID clientEventId,
            @NotNull SessionEventType type,
            @NotNull Instant occurredAt) {
    }

    public record IngestRequest(
            @NotEmpty @Size(max = 500) @Valid List<SessionEventInput> events) {
    }

    public record IngestResponse(int accepted, int duplicates, int rejected) {
    }

    // ---------- Admin read ----------

    public record TimelineEntry(Long id, SessionEventType type, SessionEventType.Category category, Instant at) {
        static TimelineEntry from(SessionEvent e) {
            return new TimelineEntry(e.getId(), e.getEventType(), e.getCategory(), e.getOccurredAt());
        }
    }

    public record TimelineResponse(Long employeeId, String date, String timezone, List<TimelineEntry> entries) {
    }

    public record SessionSummaryResponse(
            Long employeeId, String date, String timezone,
            Instant sessionStart, Instant sessionEnd,
            long sessionSeconds, long activeSeconds, long idleSeconds, long lockedSeconds,
            boolean currentlyOnline) {
    }
}
