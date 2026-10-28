package com.employeemonitor.api.webaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One rule in a policy: a canonical domain (host-suffix match) OR a category, plus the action to take. */
@Entity
@Table(name = "web_policy_rules")
public class WebPolicyRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    private String domain;

    private String category;

    @Column(nullable = false)
    private String action;   // WebAction name

    protected WebPolicyRule() {
    }

    public WebPolicyRule(Long policyId, String domain, String category, WebAction action) {
        this.policyId = policyId;
        this.domain = domain;
        this.category = category;
        this.action = action.name();
    }

    public Long getId() {
        return id;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public String getDomain() {
        return domain;
    }

    public String getCategory() {
        return category;
    }

    public WebAction getAction() {
        return WebAction.valueOf(action);
    }
}
