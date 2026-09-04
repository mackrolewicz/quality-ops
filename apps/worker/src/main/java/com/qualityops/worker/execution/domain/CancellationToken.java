package com.qualityops.worker.execution.domain;

@FunctionalInterface
public interface CancellationToken {
    boolean isCancelled();

    static CancellationToken never() {
        return () -> false;
    }
}
