package com.employeemonitor.api.screenshots;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScreenshotRepository extends JpaRepository<Screenshot, Long> {

    Optional<Screenshot> findByDeviceIdAndClientEventId(Long deviceId, UUID clientEventId);

    Page<Screenshot> findByEmployeeIdAndCapturedAtBetweenOrderByCapturedAtDesc(
            Long employeeId, Instant from, Instant to, Pageable pageable);

    @Query("select s from Screenshot s where s.capturedAt < :cutoff")
    List<Screenshot> findExpired(Instant cutoff, Pageable pageable);
}
