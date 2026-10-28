package com.employeemonitor.api.webaccess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainMatcherTest {

    @Test
    void canonicalizeStripsCaseTrailingDotSchemePathPort() {
        assertEquals("www.example.com", DomainMatcher.canonicalize("WWW.Example.COM."));
        assertEquals("example.com", DomainMatcher.canonicalize("https://example.com/path?q=1"));
        assertEquals("host.com", DomainMatcher.canonicalize("user@host.com:8080"));
        assertEquals("example.com", DomainMatcher.canonicalize("  .example.com. "));
        assertEquals("", DomainMatcher.canonicalize(null));
        assertEquals("", DomainMatcher.canonicalize("   "));
    }

    @Test
    void matchesHostAndSubdomainsButNotLookalikes() {
        assertTrue(DomainMatcher.matches("example.com", "example.com"));
        assertTrue(DomainMatcher.matches("example.com", "www.example.com"));
        assertTrue(DomainMatcher.matches("example.com", "shop.example.com"));
        assertTrue(DomainMatcher.matches("example.com", "a.b.example.com"));

        // The classic attack: a suffix that merely contains the domain must NOT match.
        assertFalse(DomainMatcher.matches("example.com", "example.com.attacker.test"));
        assertFalse(DomainMatcher.matches("example.com", "notexample.com"));
        assertFalse(DomainMatcher.matches("example.com", "example.org"));
        assertFalse(DomainMatcher.matches("example.com", ""));
    }

    @Test
    void validatesRuleDomains() {
        assertTrue(DomainMatcher.isValidRuleDomain("example.com"));
        assertTrue(DomainMatcher.isValidRuleDomain("mail.google.co.uk"));
        assertFalse(DomainMatcher.isValidRuleDomain("localhost"));       // single label
        assertFalse(DomainMatcher.isValidRuleDomain("*.example.com"));    // wildcard
        assertFalse(DomainMatcher.isValidRuleDomain("bad_underscore.com"));
        assertFalse(DomainMatcher.isValidRuleDomain("-leading.com"));
        assertFalse(DomainMatcher.isValidRuleDomain(""));
    }
}
