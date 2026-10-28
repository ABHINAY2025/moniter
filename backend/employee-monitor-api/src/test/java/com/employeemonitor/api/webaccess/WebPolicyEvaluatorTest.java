package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.webaccess.WebPolicyEvaluator.Decision;
import com.employeemonitor.api.webaccess.WebPolicyEvaluator.EffectiveRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPolicyEvaluatorTest {

    private static EffectiveRule rule(long policy, int priority, String domain, WebAction action) {
        return new EffectiveRule(policy, policy * 100, priority, domain, action);
    }

    @Test
    void noMatchingRuleIsUnrestricted() {
        Decision d = WebPolicyEvaluator.decide(List.of(rule(1, 100, "example.com", WebAction.BLOCK)), "other.com");
        assertEquals(Decision.NO_MATCH, d);
        assertNull(d.action());
    }

    @Test
    void higherPriorityWins() {
        var rules = List.of(
                rule(1, 50, "example.com", WebAction.ALLOW),
                rule(2, 200, "example.com", WebAction.BLOCK));
        assertEquals(WebAction.BLOCK, WebPolicyEvaluator.decide(rules, "www.example.com").action());
    }

    @Test
    void moreSpecificRuleWinsWithinSamePriority() {
        var rules = List.of(
                rule(1, 100, "example.com", WebAction.BLOCK),
                rule(1, 100, "mail.example.com", WebAction.ALLOW));
        // mail.example.com is allowed (more specific), but another subdomain is blocked.
        assertEquals(WebAction.ALLOW, WebPolicyEvaluator.decide(rules, "mail.example.com").action());
        assertEquals(WebAction.BLOCK, WebPolicyEvaluator.decide(rules, "shop.example.com").action());
    }

    @Test
    void allowExceptionBeatsBlockAtSamePriorityAndSpecificity() {
        var rules = List.of(
                rule(1, 100, "example.com", WebAction.BLOCK),
                rule(2, 100, "example.com", WebAction.ALLOW));
        Decision d = WebPolicyEvaluator.decide(rules, "example.com");
        assertEquals(WebAction.ALLOW, d.action());
        assertTrue(d.policyId() != null);
    }

    @Test
    void blockDecisionReportsBlock() {
        Decision d = WebPolicyEvaluator.decide(List.of(rule(1, 100, "tiktok.com", WebAction.BLOCK)),
                "www.tiktok.com");
        assertTrue(d.isBlock());
    }
}
