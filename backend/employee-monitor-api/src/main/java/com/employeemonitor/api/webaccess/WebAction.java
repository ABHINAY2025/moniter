package com.employeemonitor.api.webaccess;

/** What a web-policy rule does for a matching domain. ALLOW is an explicit exception that can override a BLOCK. */
public enum WebAction {
    ALLOW,
    BLOCK,
    MONITOR
}
