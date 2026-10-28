package com.employeemonitor.api.webaccess;

import java.util.List;

/**
 * Resolves the effective action for a host against a device's effective rule set, deterministically.
 *
 * <p>Among the rules whose domain matches the host, the winner is the one with, in order:
 * <ol>
 *   <li>the highest policy priority,</li>
 *   <li>the most specific match (longer rule domain, e.g. {@code mail.google.com} beats {@code google.com}),</li>
 *   <li>then an explicit {@code ALLOW} (exception) over {@code BLOCK} over {@code MONITOR}.</li>
 * </ol>
 * No matching rule means {@link Decision#NO_MATCH} (unrestricted). This is pure and fully unit-tested.
 */
public final class WebPolicyEvaluator {

    private WebPolicyEvaluator() {
    }

    public record EffectiveRule(long policyId, long ruleId, int priority, String domain, WebAction action) {
    }

    public record Decision(WebAction action, Long policyId, Long ruleId) {
        public static final Decision NO_MATCH = new Decision(null, null, null);

        public boolean isBlock() {
            return action == WebAction.BLOCK;
        }
    }

    public static Decision decide(List<EffectiveRule> rules, String host) {
        String h = DomainMatcher.canonicalize(host);
        if (h.isEmpty()) {
            return Decision.NO_MATCH;
        }
        EffectiveRule best = null;
        for (EffectiveRule r : rules) {
            if (DomainMatcher.matches(r.domain(), h) && (best == null || better(r, best))) {
                best = r;
            }
        }
        return best == null ? Decision.NO_MATCH : new Decision(best.action(), best.policyId(), best.ruleId());
    }

    private static boolean better(EffectiveRule a, EffectiveRule b) {
        if (a.priority() != b.priority()) {
            return a.priority() > b.priority();
        }
        int sa = DomainMatcher.canonicalize(a.domain()).length();
        int sb = DomainMatcher.canonicalize(b.domain()).length();
        if (sa != sb) {
            return sa > sb;
        }
        return rank(a.action()) < rank(b.action());
    }

    private static int rank(WebAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case BLOCK -> 1;
            case MONITOR -> 2;
        };
    }
}
