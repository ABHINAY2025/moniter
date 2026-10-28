package com.employeemonitor.api.devices;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeviceRepository extends JpaRepository<Device, Long>, JpaSpecificationExecutor<Device> {

    @Query(value = "select nextval('device_code_seq')", nativeQuery = true)
    long nextDeviceNumber();

    Optional<Device> findFirstByHardwareIdAndStatusNot(String hardwareId, Device.Status status);

    List<Device> findByEmployeeIdAndStatusNotOrderByRegisteredAt(Long employeeId, Device.Status status);

    List<Device> findByStatus(Device.Status status);

    List<Device> findByStatusAndEmployeeIdIn(Device.Status status, Collection<Long> employeeIds);

    long countByStatus(Device.Status status);

    @Modifying
    @Query("""
            update Device d set d.lastSeenAt = :now,
                d.ipAddress = coalesce(:ip, d.ipAddress),
                d.agentVersion = coalesce(:agentVersion, d.agentVersion)
            where d.id = :id
            """)
    int recordHeartbeat(Long id, Instant now, String ip, String agentVersion);
}
