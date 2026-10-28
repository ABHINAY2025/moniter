package com.employeemonitor.api.employees;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ManagerAssignmentRepository extends JpaRepository<ManagerAssignment, ManagerAssignment.Key> {

    List<ManagerAssignment> findByManagerUserId(Long managerUserId);

    boolean existsByManagerUserIdAndEmployeeId(Long managerUserId, Long employeeId);
}
