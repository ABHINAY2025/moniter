package com.employeemonitor.api.updates;

import java.util.Comparator;
import java.util.regex.Pattern;

/** Minimal dotted numeric version (major.minor.patch) comparison for the update flow. */
public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    private static final Pattern PATTERN = Pattern.compile("\\d+(\\.\\d+){0,2}");

    public static SemanticVersion parse(String value) {
        if (value == null || !PATTERN.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("Invalid version: " + value);
        }
        String[] parts = value.trim().split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return new SemanticVersion(major, minor, patch);
    }

    public static boolean isValid(String value) {
        return value != null && PATTERN.matcher(value.trim()).matches();
    }

    @Override
    public int compareTo(SemanticVersion o) {
        return Comparator.comparingInt(SemanticVersion::major)
                .thenComparingInt(SemanticVersion::minor)
                .thenComparingInt(SemanticVersion::patch)
                .compare(this, o);
    }
}
