package com.employeemonitor.api.monitoring;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface WebsiteUsageRepository extends JpaRepository<WebsiteUsage, Long> {

    @Query("select u.clientEventId from WebsiteUsage u where u.deviceId = :deviceId and u.clientEventId in :ids")
    Set<UUID> findExistingClientIds(Long deviceId, Collection<UUID> ids);

    @Query("""
            select new com.employeemonitor.api.monitoring.UsageSlice(
                u.domain, sum(u.durationSeconds), count(u))
            from WebsiteUsage u
            where u.employeeId = :employeeId and u.startedAt >= :from and u.startedAt < :to
            group by u.domain
            order by sum(u.durationSeconds) desc
            """)
    List<UsageSlice> aggregate(Long employeeId, Instant from, Instant to);
}
