package com.qualityops.api.audit.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a {@code qualityops.slow_op} Micrometer timer for the annotated method
 * and, when the call exceeds {@link #slowThresholdMillis()}, increments
 * {@code qualityops.slow_op.exceeded} and logs a WARN (ADR-008 &sect;7). Handled
 * by {@code com.qualityops.api.audit.aspect.TimingAspect} ({@code @Around},
 * {@code @Order(0)} &mdash; outermost, so it also covers any {@code @Audited}
 * I/O on the same method).
 *
 * <p>Deliberately distinct from {@code io.micrometer.core.annotation.Timed}: the
 * threshold + WARN semantics are explicit and there is no classpath ambiguity.
 * Subject to the same self-invocation limitation as {@link Audited}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Timed {

    String value();

    /**
     * Per-op slow threshold in milliseconds. {@code 0} (the default) means "use
     * the configured global default" ({@code qualityops.timing.slow-threshold-ms}).
     */
    long slowThresholdMillis() default 0;
}
