package com.employeemonitor.api.devices;

import com.employeemonitor.api.security.DevicePrincipal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    @Query("""
            select new com.employeemonitor.api.security.DevicePrincipal(d.id, d.deviceCode, e.id, t.id)
            from DeviceToken t join Device d on d.id = t.deviceId left join d.employee e
            where t.tokenHash = :hash
              and t.revokedAt is null
              and (t.expiresAt is null or t.expiresAt > :now)
              and d.status = :status
            """)
    Optional<DevicePrincipal> findValidPrincipal(String hash, Instant now, Device.Status status);

    @Modifying
    @Query("""
            update DeviceToken t set t.lastUsedAt = :now
            where t.id = :id and (t.lastUsedAt is null or t.lastUsedAt < :threshold)
            """)
    int touch(Long id, Instant now, Instant threshold);

    @Modifying
    @Query("update DeviceToken t set t.revokedAt = :now where t.deviceId = :deviceId and t.revokedAt is null")
    int revokeAllForDevice(Long deviceId, Instant now);
}
