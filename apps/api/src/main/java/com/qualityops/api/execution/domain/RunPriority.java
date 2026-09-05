package com.qualityops.api.execution.domain;

public enum RunPriority {
    HIGH, NORMAL, LOW;

    /** Null / blank -> NORMAL. Throws IllegalArgumentException on an unknown value. */
    public static RunPriority fromNullable(String raw) {
        return raw == null || raw.isBlank() ? NORMAL : RunPriority.valueOf(raw);
    }
}
