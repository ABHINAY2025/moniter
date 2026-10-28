package com.employeemonitor.api.policies;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitoringPolicyRepository extends JpaRepository<MonitoringPolicy, Long> {

    Optional<MonitoringPolicy> findByDefaultPolicyTrue();

    List<MonitoringPolicy> findByDeviceSpecificFalseOrderByName();
}
