package com.qualityops.api.execution.domain;

import java.util.Set;

public enum QueueState {
    QUEUED, DISPATCHED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public static final Set<QueueState> ACTIVE = Set.of(DISPATCHED, RUNNING);
    public static final Set<QueueState> TERMINAL = Set.of(COMPLETED, FAILED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
