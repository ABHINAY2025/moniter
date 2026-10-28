package com.employeemonitor.api.monitoring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ApplicationUsageRepository extends JpaRepository<ApplicationUsage, Long> {

    @Query("select u.clientEventId from ApplicationUsage u where u.deviceId = :deviceId and u.clientEventId in :ids")
    Set<UUID> findExistingClientIds(Long deviceId, Collection<UUID> ids);

    @Query("""
            select new com.employeemonitor.api.monitoring.UsageSlice(
                u.applicationName, sum(u.durationSeconds), count(u))
            from ApplicationUsage u
            where u.employeeId = :employeeId and u.startedAt >= :from and u.startedAt < :to
            group by u.applicationName
            order by sum(u.durationSeconds) desc
            """)
    List<UsageSlice> aggregate(Long employeeId, Instant from, Instant to);
}
