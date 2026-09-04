package com.qualityops.worker.execution.domain;

/** Worker-internal judgement on whether re-running a failed case could
 *  double-charge an external side effect. NEVER serialised — not on any event,
 *  not on {@link CaseExecutionResult} once it leaves the worker, not on a
 *  {@code CaseResultSummary}. A case is retried only when its status is
 *  {@code TIMEOUT}/{@code ERROR} <em>and</em> this is {@link #NONE_OBSERVED}. */
public enum SideEffectClass {
    /** No response was seen / the operation is idempotent by method — safe to retry. */
    NONE_OBSERVED,
    /** A response may have started or a non-idempotent body was fully sent — do not retry. */
    POSSIBLE
}
