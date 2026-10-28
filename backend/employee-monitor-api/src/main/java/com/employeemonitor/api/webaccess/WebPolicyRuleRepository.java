package com.employeemonitor.api.webaccess;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface WebPolicyRuleRepository extends JpaRepository<WebPolicyRule, Long> {

    List<WebPolicyRule> findByPolicyId(Long policyId);

    List<WebPolicyRule> findByPolicyIdIn(Collection<Long> policyIds);

    void deleteByPolicyId(Long policyId);
}
