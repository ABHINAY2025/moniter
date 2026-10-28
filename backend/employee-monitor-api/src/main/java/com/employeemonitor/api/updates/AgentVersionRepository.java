package com.employeemonitor.api.updates;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentVersionRepository extends JpaRepository<AgentVersion, Long> {

    Optional<AgentVersion> findByVersion(String version);

    boolean existsByVersion(String version);

    List<AgentVersion> findByApprovedTrueOrderByReleasedAtDesc();

    List<AgentVersion> findAllByOrderByReleasedAtDesc();
}
