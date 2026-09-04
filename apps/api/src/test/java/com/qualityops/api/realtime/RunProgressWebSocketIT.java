package com.qualityops.api.realtime;

import com.qualityops.api.execution.application.port.out.RunProgressNotifier;
import com.qualityops.api.execution.application.port.out.RunProgressEvent;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.StompSession;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy; // used by subscribeToAnotherOrgsRun_isDenied

/**
 * WP5 (ADR-008 §5) — end-to-end STOMP-over-SockJS delivery: JWT on {@code CONNECT},
 * org-checked {@code SUBSCRIBE}, and a {@link RunProgressEvent} pushed through the
 * {@link RunProgressNotifier} → Redis channel → {@code RedisRunEventBridge} →
 * {@code /topic/runs/{runId}} path.
 */
class RunProgressWebSocketIT extends RealtimeWebSocketITBase {

    @Autowired
    RunProgressNotifier runProgressNotifier;

    @Test
    void subscribeOwnRun_thenStatusPublished_receivesStatusFrame() throws Exception {
        UUID orgId = ItFixtures.insertOrg(jdbc);
        UUID userId = ItFixtures.insertUser(jdbc, orgId);
        UUID projectId = ItFixtures.insertProject(jdbc, orgId);
        UUID suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        UUID envId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        UUID runId = seedRunningRun(orgId, projectId, suiteId, envId, userId);

        var handler = connect(tokenFor(userId, orgId));
        StompSession session = handler.connected.get(5, TimeUnit.SECONDS);
        var received = subscribeRunTopic(session, runId);
        // let the SUBSCRIBE frame round-trip before the publish
        Thread.sleep(300);

        runProgressNotifier.publish(RunProgressEvent.status(
            runId, orgId, "PASSED", "COMPLETED", Instant.now()));

        RunProgressEvent frame = received.get(5, TimeUnit.SECONDS);
        assertThat(frame.runId()).isEqualTo(runId);
        assertThat(frame.type()).isEqualTo("STATUS");
        assertThat(frame.status()).isEqualTo("PASSED");
        session.disconnect();
    }

    @Test
    void connect_withoutToken_isRejected() throws Exception {
        var handler = connect(null);

        Throwable rejection = handler.error.get(15, TimeUnit.SECONDS);
        assertThat(rejection).isNotNull();
        assertThat(handler.connected.isDone()).isFalse();
    }

    @Test
    void connect_withExpiredToken_isRejected() throws Exception {
        UUID orgId = ItFixtures.insertOrg(jdbc);
        UUID userId = ItFixtures.insertUser(jdbc, orgId);

        var handler = connect(expiredToken(userId, orgId));

        Throwable rejection = handler.error.get(15, TimeUnit.SECONDS);
        assertThat(rejection).isNotNull();
        assertThat(handler.connected.isDone()).isFalse();
    }

    @Test
    void subscribeToAnotherOrgsRun_isDenied_noFrameDelivered() throws Exception {
        UUID orgA = ItFixtures.insertOrg(jdbc);
        UUID userA = ItFixtures.insertUser(jdbc, orgA);
        UUID orgB = ItFixtures.insertOrg(jdbc);
        UUID userB = ItFixtures.insertUser(jdbc, orgB);
        UUID projectB = ItFixtures.insertProject(jdbc, orgB);
        UUID suiteB = ItFixtures.insertSuite(jdbc, orgB, projectB);
        UUID envB = ItFixtures.insertEnvironment(jdbc, orgB, projectB);
        UUID runB = seedRunningRun(orgB, projectB, suiteB, envB, userB);

        var handler = connect(tokenFor(userA, orgA));
        StompSession session = handler.connected.get(5, TimeUnit.SECONDS);
        var received = subscribeRunTopic(session, runB);
        Thread.sleep(300);

        runProgressNotifier.publish(RunProgressEvent.status(
            runB, orgB, "PASSED", "COMPLETED", Instant.now()));

        assertThatThrownBy(() -> received.get(2, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);
    }
}
