package com.employeemonitor.api.sessions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

    @Query("select e.clientEventId from SessionEvent e where e.deviceId = :deviceId and e.clientEventId in :ids")
    Set<UUID> findExistingClientIds(Long deviceId, Collection<UUID> ids);

    List<SessionEvent> findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
            Long employeeId, Instant from, Instant to);

    /** State entering the window: the most recent event strictly before {@code before}. */
    SessionEvent findFirstByEmployeeIdAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(Long employeeId, Instant before);

    List<SessionEvent> findByDeviceIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(
            Long deviceId, Instant from, Instant to);
}
