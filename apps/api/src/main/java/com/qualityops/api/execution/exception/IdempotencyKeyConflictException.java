package com.qualityops.api.execution.exception;

import com.qualityops.api.common.ConflictException;

/** ADR-007 §5 — same {@code Idempotency-Key}, different request fingerprint.
 *  Handled as 409 by {@code GlobalExceptionHandler.handleDomainConflict}. */
public class IdempotencyKeyConflictException extends ConflictException {

    public IdempotencyKeyConflictException(String key) {
        super("IDEMPOTENCY_KEY_CONFLICT", "Idempotency-Key already used for a different request: " + key);
    }
}
