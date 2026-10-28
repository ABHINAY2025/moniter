package com.employeemonitor.api.webaccess;

import java.util.List;

/**
 * Essential operating-system, security, identity and company-management domains that must not be blocked by accident.
 * A BLOCK rule covering any of these is rejected unless the administrator sets an explicit override on the policy.
 * This is a safety floor, not a full list; extend via configuration if needed.
 */
public final class CriticalDomains {

    private CriticalDomains() {
    }

    private static final List<String> CRITICAL = List.of(
            // Windows / Microsoft update & telemetry & identity
            "microsoft.com", "windowsupdate.com", "windows.com", "live.com", "office.com", "office365.com",
            "microsoftonline.com", "msftconnecttest.com", "msftncsi.com", "windows.net", "azure.com",
            "microsoftedge.com", "skype.com",
            // Security / AV / endpoint
            "defender.microsoft.com", "wdcp.microsoft.com", "zscaler.com", "zscloud.net", "mcafee.com",
            "symantec.com", "crowdstrike.com", "sophos.com",
            // Certificate / time / connectivity infrastructure
            "digicert.com", "verisign.com", "globalsign.com", "letsencrypt.org", "ocsp.pki.goog", "ntp.org",
            // This platform's own backend host is protected implicitly at the agent; identity providers:
            "okta.com", "duosecurity.com");

    /** True if blocking {@code domain} would hit an essential service (exact or parent-of a critical domain). */
    public static boolean isCritical(String domain) {
        String d = DomainMatcher.canonicalize(domain);
        if (d.isEmpty()) {
            return false;
        }
        for (String c : CRITICAL) {
            // the rule hits a critical domain if the critical domain equals or is a subdomain of the rule
            if (DomainMatcher.matches(d, c) || d.equals(c)) {
                return true;
            }
        }
        return false;
    }

    public static List<String> all() {
        return CRITICAL;
    }
}
