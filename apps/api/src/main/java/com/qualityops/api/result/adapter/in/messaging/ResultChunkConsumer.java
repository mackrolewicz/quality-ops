package com.qualityops.api.result.adapter.in.messaging;

import com.qualityops.api.result.application.port.in.RecordCaseResultChunkUseCase;
import com.qualityops.events.ResultChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Per-case streaming updates on {@code results.chunk}, group {@code api-results}
 * — the same group that consumes {@code runs.completed}, so one API instance
 * owns both a run's chunks and its terminal (co-partitioned by runId). Applies
 * the same org- and executionId-guarded, epoch-monotone upsert as the terminal;
 * failures retry 3×1s then land on {@code results.chunk.DLT}.
 */
@Component
public class ResultChunkConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResultChunkConsumer.class);

    private final RecordCaseResultChunkUseCase recordCaseResultChunkUseCase;

    public ResultChunkConsumer(RecordCaseResultChunkUseCase recordCaseResultChunkUseCase) {
        this.recordCaseResultChunkUseCase = recordCaseResultChunkUseCase;
    }

    @KafkaListener(topics = "results.chunk", groupId = "api-results")
    public void handle(ResultChunkEvent event) {
        log.debug("Received results.chunk for run {} case {} epoch {}",
            event.runId(), event.testCaseId(), event.attemptEpoch());
        recordCaseResultChunkUseCase.recordChunk(event);
    }
}
