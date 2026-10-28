package com.employeemonitor.api.webaccess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WebPolicyAssignmentRepository extends JpaRepository<WebPolicyAssignment, Long> {

    List<WebPolicyAssignment> findByPolicyId(Long policyId);

    boolean existsByPolicyIdAndTargetTypeAndTargetId(Long policyId, String targetType, Long targetId);

    /** Assignments that apply to a device: a direct DEVICE assignment or an EMPLOYEE assignment for its owner. */
    @Query("""
            select a from WebPolicyAssignment a
            where (a.targetType = 'DEVICE' and a.targetId = :deviceId)
               or (a.targetType = 'EMPLOYEE' and :employeeId is not null and a.targetId = :employeeId)
            """)
    List<WebPolicyAssignment> findForDevice(@Param("deviceId") Long deviceId, @Param("employeeId") Long employeeId);
}
