package com.employeemonitor.api.presence;

/** Activity state reported by the agent. OFFLINE is derived server-side from missing heartbeats. */
public enum ActivityStatus {
    ACTIVE,
    IDLE,
    LOCKED,
    OFFLINE;

    /** Higher rank wins when summarising an employee with several devices. */
    public int rank() {
        return switch (this) {
            case ACTIVE -> 3;
            case IDLE -> 2;
            case LOCKED -> 1;
            case OFFLINE -> 0;
        };
    }
}
