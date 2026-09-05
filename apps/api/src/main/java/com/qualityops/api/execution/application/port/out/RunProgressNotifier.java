package com.qualityops.api.execution.application.port.out;


/**
 * Outbound port for pushing lightweight run status/progress frames to any
 * connected dashboard (ADR-008 §5). Defined in {@code execution} so the
 * lifecycle service depends inward on an interface; the {@code result} module
 * (which already depends on {@code execution}) reuses it for per-case frames.
 *
 * <p>Implementations MUST be best-effort: a failure to publish must never
 * propagate out of a Kafka consumer transaction. Callers still wrap every
 * invocation defensively.
 */
public interface RunProgressNotifier {

    void publish(RunProgressEvent event);
}
