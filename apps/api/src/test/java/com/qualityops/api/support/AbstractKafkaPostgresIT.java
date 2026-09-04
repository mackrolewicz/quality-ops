package com.qualityops.api.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Base for integration tests that exercise the in-process Kafka consumers end to
 * end. Adds an embedded broker and turns the listeners back on;
 * {@code auto-offset-reset=earliest} keeps the test deterministic if a producer
 * beats consumer partition assignment.
 */
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=true",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    // B7 (ADR-006): re-enable admin topic auto-create here (overrides the false
    // set on AbstractPostgresIT) — @EmbeddedKafka provides every topic.
    "spring.kafka.admin.auto-create=true",
    // Phase 2C (ADR-006): the tick/dispatch/maintenance @Scheduled jobs stay off
    // their own timers here too; dispatch ITs call dispatchAvailable() directly.
    "qualityops.scheduling.jobs-enabled=false",
    // Phase 2E (ADR-008): a subclass @SpringBootTest replaces (not merges) the
    // AbstractPostgresIT properties array, so the Redis-dependent 2E features
    // must be re-disabled here. The WebSocket Redis pub/sub bridge in
    // particular eager-connects on context refresh.
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=false"
})
@EmbeddedKafka(
    partitions = 1,
    topics = {"runs.requested", "runs.started", "runs.completed", "runs.failed",
        "results.chunk", "results.chunk.DLT", "runs.cancel", "runs.cancel.DLT"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
public abstract class AbstractKafkaPostgresIT extends AbstractPostgresIT {
}
