package com.employeemonitor.api.webaccess;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebPolicyRepository extends JpaRepository<WebPolicy, Long> {

    Page<WebPolicy> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
