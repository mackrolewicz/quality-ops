package com.qualityops.api.audit.domain;

/**
 * Centralised vocabulary for {@code audit_log.action} values (ADR-008 &sect;7).
 * The {@code @Audited} annotation carries the raw string; this class keeps the
 * set of known actions in one place.
 */
public final class AuditAction {

    public static final String ORG_RUN_CONCURRENCY_UPDATE = "org.run_concurrency.update";
    public static final String ENVIRONMENT_CREATE = "environment.create";
    public static final String ENVIRONMENT_UPDATE = "environment.update";
    public static final String ENVIRONMENT_DELETE = "environment.delete";
    public static final String PROJECT_DELETE = "project.delete";
    public static final String TEST_SUITE_DELETE = "test_suite.delete";
    public static final String WEBHOOK_ENDPOINT_REGISTER = "webhook_endpoint.register";
    public static final String WEBHOOK_ENDPOINT_DELETE = "webhook_endpoint.delete";
    public static final String SCM_CONNECTION_CREATE = "scm.connection.create";
    public static final String SCM_CONNECTION_UPDATE = "scm.connection.update";
    public static final String SCM_CONNECTION_DELETE = "scm.connection.delete";
    public static final String SCM_CONNECTION_TEST = "scm.connection.test";

    private AuditAction() {
    }
}
