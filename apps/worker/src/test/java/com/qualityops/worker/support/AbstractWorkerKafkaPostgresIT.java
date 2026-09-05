package com.qualityops.worker.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

// A subclass @SpringBootTest replaces (does not merge) the parent's `properties`,
// so the artifact/MinIO opt-outs from AbstractWorkerPostgresIT must be repeated here:
// no IT constructs a MinIO client or runs BucketBootstrap (ADR-005 §5, watch-out #13).
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=true",
    "spring.kafka.consumer.auto-offset-reset=earliest",
    // B7 (ADR-006): @EmbeddedKafka provides every topic — admin auto-create is safe here.
    "spring.kafka.admin.auto-create=true",
    "qualityops.worker.execution.artifacts.enabled=false",
    "qualityops.worker.execution.artifacts.bootstrap-enabled=false",
    "qualityops.repo-exec.enabled=false"
})
@EmbeddedKafka(partitions = 1,
    topics = {"runs.requested", "runs.started", "runs.completed", "runs.failed", "results.chunk",
        "runs.cancel", "runs.cancel.DLT"},
    bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class AbstractWorkerKafkaPostgresIT extends AbstractWorkerPostgresIT {
}
