package com.employeemonitor.api.liveview;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LiveViewSessionRepository extends JpaRepository<LiveViewSession, Long> {

    Optional<LiveViewSession> findBySessionId(UUID sessionId);

    Optional<LiveViewSession> findByDeviceIdAndStatusNot(Long deviceId, LiveViewSession.Status status);

    List<LiveViewSession> findByStatusNot(LiveViewSession.Status status);

    boolean existsByDeviceIdAndStatusNot(Long deviceId, LiveViewSession.Status status);

    Page<LiveViewSession> findAllByOrderByStartedAtDesc(Pageable pageable);
}
