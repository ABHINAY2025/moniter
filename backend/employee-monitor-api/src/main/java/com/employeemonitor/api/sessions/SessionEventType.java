package com.employeemonitor.api.sessions;

/**
 * Transition events reported by the agent. Each maps to the presence state that becomes effective at the
 * event's timestamp; {@code null} means "marker only" (does not itself change the accumulated state).
 */
public enum SessionEventType {

    LOGIN(Category.SESSION, PresenceState.ACTIVE),
    LOGOUT(Category.SESSION, PresenceState.OFFLINE),
    LOCK(Category.SESSION, PresenceState.LOCKED),
    UNLOCK(Category.SESSION, PresenceState.ACTIVE),
    SHUTDOWN(Category.SESSION, PresenceState.OFFLINE),

    SLEEP(Category.POWER, PresenceState.OFFLINE),
    RESUME(Category.POWER, PresenceState.ACTIVE),

    ACTIVE(Category.ACTIVITY, PresenceState.ACTIVE),
    IDLE(Category.ACTIVITY, PresenceState.IDLE),

    // Lifecycle markers. AGENT_START does not set a state; the agent emits an activity event right after it.
    AGENT_START(Category.SESSION, null),
    AGENT_STOP(Category.SESSION, PresenceState.OFFLINE);

    public enum Category { SESSION, ACTIVITY, POWER }

    /** Coarse presence used only for duration accounting (distinct from the realtime Redis presence). */
    public enum PresenceState { ACTIVE, IDLE, LOCKED, OFFLINE }

    private final Category category;
    private final PresenceState resultingState;

    SessionEventType(Category category, PresenceState resultingState) {
        this.category = category;
        this.resultingState = resultingState;
    }

    public Category category() {
        return category;
    }

    public PresenceState resultingState() {
        return resultingState;
    }
}
