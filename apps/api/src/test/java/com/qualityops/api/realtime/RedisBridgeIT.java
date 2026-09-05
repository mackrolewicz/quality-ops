package com.qualityops.api.realtime;

import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.StompSession;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP5 (ADR-008 §5) — proves the cross-replica fan-out: a {@link RunProgressEvent}
 * published straight onto the shared Redis channel (as a peer API replica would)
 * is delivered by {@code RedisRunEventBridge} to a locally-subscribed STOMP
 * client. No Kafka involved.
 */
class RedisBridgeIT extends RealtimeWebSocketITBase {

    @Test
    void publishToRedisChannelDirectly_subscribedClient_receivesFrame() throws Exception {
        UUID orgId = ItFixtures.insertOrg(jdbc);
        UUID userId = ItFixtures.insertUser(jdbc, orgId);
        UUID projectId = ItFixtures.insertProject(jdbc, orgId);
        UUID suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        UUID envId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        UUID runId = seedRunningRun(orgId, projectId, suiteId, envId, userId);

        var handler = connect(tokenFor(userId, orgId));
        StompSession session = handler.connected.get(5, TimeUnit.SECONDS);
        var received = subscribeRunTopic(session, runId);
        Thread.sleep(300);

        var event = RunProgressEvent.status(runId, orgId, "RUNNING", "RUNNING", Instant.now());
        redis.convertAndSend(wsProps.redisChannel(), objectMapper.writeValueAsString(event));

        RunProgressEvent frame = received.get(5, TimeUnit.SECONDS);
        assertThat(frame.runId()).isEqualTo(runId);
        assertThat(frame.status()).isEqualTo("RUNNING");
        session.disconnect();
    }
}
