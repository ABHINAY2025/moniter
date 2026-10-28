package com.employeemonitor.api.monitoring;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface NetworkEventRepository extends JpaRepository<NetworkEvent, Long> {

    @Query("select n.clientEventId from NetworkEvent n where n.deviceId = :deviceId and n.clientEventId in :ids")
    Set<UUID> findExistingClientIds(Long deviceId, Collection<UUID> ids);

    Page<NetworkEvent> findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long employeeId, Instant from, Instant to, Pageable pageable);
}
