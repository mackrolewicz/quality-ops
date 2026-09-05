package com.qualityops.api.realtime.adapter.out;

import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Fallback {@link RunProgressNotifier} active only when
 * {@code qualityops.ws.enabled=false} (the ~200 Redis-free integration tests).
 * Keeps {@code RunLifecycleService} / {@code ResultService} wireable without the
 * WebSocket broker or a {@code SimpMessagingTemplate} on the classpath.
 */
@Component
@ConditionalOnProperty(name = "qualityops.ws.enabled", havingValue = "false")
public class NoOpRunProgressNotifier implements RunProgressNotifier {

    private static final Logger log = LoggerFactory.getLogger(NoOpRunProgressNotifier.class);

    @Override
    public void publish(RunProgressEvent event) {
        log.trace("WS disabled — dropping run-progress event for run {}", event.runId());
    }
}
