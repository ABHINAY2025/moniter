package com.employeemonitor.api.monitoring;

/** Aggregated usage for one application or domain over a time window. */
public record UsageSlice(String label, Long totalSeconds, Long sessions) {
}
