package com.employeemonitor.api.monitoring;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ZscalerEventRepository extends JpaRepository<ZscalerEvent, Long> {

    @Query("select z.clientEventId from ZscalerEvent z where z.deviceId = :deviceId and z.clientEventId in :ids")
    Set<UUID> findExistingClientIds(Long deviceId, Collection<UUID> ids);

    Page<ZscalerEvent> findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long employeeId, Instant from, Instant to, Pageable pageable);

    Optional<ZscalerEvent> findFirstByEmployeeIdOrderByOccurredAtDesc(Long employeeId);
}
