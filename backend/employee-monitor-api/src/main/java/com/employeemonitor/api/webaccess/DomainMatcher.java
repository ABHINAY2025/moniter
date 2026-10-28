package com.employeemonitor.api.webaccess;

import java.net.IDN;

/**
 * Canonicalises host names and decides host-suffix (subdomain-safe) matches. A rule domain matches a host when the
 * host equals it or is a subdomain of it: {@code example.com} covers {@code www.example.com} and {@code shop.example.com}
 * but NOT {@code example.com.attacker.test}. Handles case, trailing dots, ports, stray scheme/path, and IDNs.
 */
public final class DomainMatcher {

    private DomainMatcher() {
    }

    /** Lower-cased ASCII host with scheme/path/userinfo/port and trailing dots removed; "" if nothing usable. */
    public static String canonicalize(String input) {
        if (input == null) {
            return "";
        }
        String s = input.trim().toLowerCase();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            return "";
        }
        try {
            s = IDN.toASCII(s);
        } catch (RuntimeException ignored) {
            // leave as-is if it isn't a valid IDN
        }
        return s.toLowerCase();
    }

    /** True when {@code host} equals {@code ruleDomain} or is a subdomain of it (both canonicalised). */
    public static boolean matches(String ruleDomain, String host) {
        String rule = canonicalize(ruleDomain);
        String h = canonicalize(host);
        if (rule.isEmpty() || h.isEmpty()) {
            return false;
        }
        return h.equals(rule) || h.endsWith("." + rule);
    }

    /** A domain is well-formed enough to store as a rule: at least two labels, valid chars, no wildcard. */
    public static boolean isValidRuleDomain(String input) {
        String s = canonicalize(input);
        if (s.isEmpty() || s.length() > 253 || s.indexOf('.') < 0) {
            return false;
        }
        for (String label : s.split("\\.")) {
            if (label.isEmpty() || label.length() > 63 || !label.matches("[a-z0-9-]+")
                    || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }
}
