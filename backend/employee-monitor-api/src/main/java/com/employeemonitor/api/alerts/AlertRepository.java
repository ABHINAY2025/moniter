package com.employeemonitor.api.alerts;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    boolean existsByDeviceIdAndCategoryAndDedupKeyAndCreatedAtAfter(Long deviceId, String category, String dedupKey,
                                                                   Instant after);

    Page<Alert> findByAcknowledgedAtIsNull(Pageable pageable);
}
