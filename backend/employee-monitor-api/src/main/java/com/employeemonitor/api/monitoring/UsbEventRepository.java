package com.employeemonitor.api.monitoring;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface UsbEventRepository extends JpaRepository<UsbEvent, Long> {

    @Query("select u.clientEventId from UsbEvent u where u.deviceId = :deviceId and u.clientEventId in :ids")
    Set<UUID> findExistingClientIds(Long deviceId, Collection<UUID> ids);

    Page<UsbEvent> findByEmployeeIdAndOccurredAtBetweenOrderByOccurredAtDesc(
            Long employeeId, Instant from, Instant to, Pageable pageable);
}
