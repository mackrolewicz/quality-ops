package com.qualityops.api.audit.aspect;

import com.qualityops.api.audit.annotation.Timed;
import com.qualityops.api.config.TimingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Records {@code qualityops.slow_op{op}} for every {@link Timed} call and, when
 * the call exceeds its threshold, increments {@code qualityops.slow_op.exceeded}
 * and logs WARN (ADR-008 &sect;7). {@code @Order(0)} &mdash; outermost, so the
 * measured wall time includes any {@link AuditAspect} I/O on the same method.
 */
@Aspect
@Component
@Order(0)
public class TimingAspect {

    private static final Logger log = LoggerFactory.getLogger(TimingAspect.class);

    private final MeterRegistry registry;
    private final TimingProperties timingProperties;

    public TimingAspect(MeterRegistry registry, TimingProperties timingProperties) {
        this.registry = registry;
        this.timingProperties = timingProperties;
    }

    @Around("@annotation(timed)")
    public Object time(ProceedingJoinPoint pjp, Timed timed) throws Throwable {
        long threshold = timed.slowThresholdMillis() > 0
            ? timed.slowThresholdMillis()
            : timingProperties.slowThresholdMs();
        long startNanos = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
            registry.timer("qualityops.slow_op", "op", timed.value())
                .record(elapsedMillis, TimeUnit.MILLISECONDS);
            if (elapsedMillis > threshold) {
                registry.counter("qualityops.slow_op.exceeded", "op", timed.value()).increment();
                log.warn("slow op {} took {}ms (threshold {}ms)", timed.value(), elapsedMillis, threshold);
            }
        }
    }
}
