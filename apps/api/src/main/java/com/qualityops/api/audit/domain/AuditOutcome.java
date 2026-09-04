package com.qualityops.api.audit.domain;

/** Terminal outcome of an {@code @Audited} call. Stored as a string in
 *  {@code audit_log.outcome} (VARCHAR + CHECK, not a PG enum). */
public enum AuditOutcome {
    SUCCESS,
    FAILURE
}
