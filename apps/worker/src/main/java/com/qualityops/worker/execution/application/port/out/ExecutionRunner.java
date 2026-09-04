package com.qualityops.worker.execution.application.port.out;

import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.exception.ExecutionHarnessException;

public interface ExecutionRunner {

    RunnerKind kind();

    /** Execute ONE snapshot case. Never throws for test failures, timeouts,
     *  blocked targets or connection errors — those are encoded in the result.
     *  Throws only for a genuine worker/harness fault. */
    CaseExecutionResult execute(CaseExecutionContext ctx) throws ExecutionHarnessException;
}
