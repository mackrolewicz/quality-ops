package com.qualityops.api.audit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose invocation must leave a durable, org-scoped
 * {@code audit_log} row (ADR-008 &sect;7). Handled by
 * {@code com.qualityops.api.audit.aspect.AuditAspect} ({@code @Around},
 * {@code @Order(10)}).
 *
 * <p><strong>Self-invocation:</strong> place this ONLY on the outermost service
 * entry point a controller (or another bean) calls. A {@code this.other()} call
 * bypasses the proxy and the aspect is silently skipped &mdash; extract the inner
 * step into its own bean instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    String action();

    String targetType() default "";
}
