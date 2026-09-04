package com.qualityops.worker.execution.application.service;

import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class AttemptRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttemptRetentionSweeper.class);

    private final ExecutionAttemptStore store;
    private final WorkerExecutionProperties props;

    public AttemptRetentionSweeper(ExecutionAttemptStore store, WorkerExecutionProperties props) {
        this.store = store;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT1H")
    void sweep() {
        int deleted = store.deleteOlderThan(Instant.now().minus(props.attemptRetention()));
        if (deleted > 0) {
            log.info("Swept {} execution_attempt rows older than {}", deleted, props.attemptRetention());
        }
    }
}
