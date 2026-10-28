package com.employeemonitor.api.webaccess;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface WebAccessEventRepository extends JpaRepository<WebAccessEvent, Long> {

    @Query("select e.clientEventId from WebAccessEvent e where e.deviceId = :deviceId and e.clientEventId in :ids")
    Set<UUID> findExistingClientIds(@Param("deviceId") Long deviceId, @Param("ids") Collection<UUID> ids);

    // The time window is always passed as concrete bounds (never NULL) by the service: a bare "(:from is null ...)"
    // on a timestamp parameter leaves PostgreSQL unable to infer the type at prepare time (ERROR 42P18), whereas the
    // direct comparison below lets it infer the type from the column.
    @Query("""
            select e from WebAccessEvent e
            where (:deviceId is null or e.deviceId = :deviceId)
              and (:employeeId is null or e.employeeId = :employeeId)
              and (:eventType is null or e.eventType = :eventType)
              and (:result is null or e.enforcementResult = :result)
              and (:domain is null or e.domain = :domain)
              and e.occurredAt >= :from
              and e.occurredAt <= :to
            """)
    Page<WebAccessEvent> search(@Param("deviceId") Long deviceId, @Param("employeeId") Long employeeId,
                                @Param("eventType") String eventType, @Param("result") String result,
                                @Param("domain") String domain, @Param("from") Instant from,
                                @Param("to") Instant to, Pageable pageable);

    @Query("select e.eventType, count(e) from WebAccessEvent e where e.occurredAt >= :from group by e.eventType")
    List<Object[]> countByEventTypeSince(@Param("from") Instant from);

    @Query("""
            select e.domain, count(e) from WebAccessEvent e
            where e.occurredAt >= :from and e.enforcementResult = 'BLOCKED'
            group by e.domain order by count(e) desc
            """)
    List<Object[]> topBlockedDomainsSince(@Param("from") Instant from, Pageable pageable);
}
