package com.qualityops.worker.execution.application.port.out;

import com.qualityops.worker.execution.domain.BrowserRunCommand;
import com.qualityops.worker.execution.domain.BrowserRunOutcome;

/** Worker-internal port isolating ALL Playwright API behind one adapter.
 *  Swappable for a separate Node runner later without touching the ExecutionRunner
 *  contract (ADR-004). */
public interface PlaywrightBrowser {

    /** Run one scenario on the driver's confined single thread. Never throws for a
     *  step/assertion failure or a scenario timeout (encoded in the outcome);
     *  throws only for an unrecoverable driver fault. */
    BrowserRunOutcome run(BrowserRunCommand command);

    /** Close + discard the shared Browser. Safe from any thread. The next
     *  {@link #run} relaunches. */
    void forceRecycle();

    /** Diagnostic: number of live {@code BrowserContext}s on the shared browser
     *  (0 when no browser is launched). Used by ITs to assert no context leaked. */
    default int openContextCount() {
        return 0;
    }
}
