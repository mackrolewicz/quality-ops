package com.qualityops.worker.execution.application.service;

/** Seam over {@link Thread#sleep(long)} so the simulation's blocking wait can be
 *  stubbed in unit tests. */
@FunctionalInterface
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;
}
