package com.qualityops.api.audit.aspect;

import com.qualityops.api.audit.application.AuditRecorder;
import com.qualityops.api.audit.support.AopTestFixtures;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.identity.domain.Role;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * ADR-008 §7 — {@code TimingAspect} ({@code @Order(0)}) wraps {@code AuditAspect}
 * ({@code @Order(10)}): the recorded wall time for the method includes the audit
 * I/O. Proven by a deliberately slow (30ms) recorder — the timer must see it.
 */
@SpringBootTest(classes = AopOrderingTest.Config.class)
class AopOrderingTest {

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @Import(AopTestFixtures.class)
    static class Config {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TimingAspect timingAspect(MeterRegistry registry) {
            return new TimingAspect(registry, new com.qualityops.api.config.TimingProperties(1000));
        }

        @Bean
        AuditAspect auditAspect(AuditRecorder recorder) {
            return new AuditAspect(recorder, new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }

    @MockBean private AuditRecorder recorder;
    @Autowired private MeterRegistry registry;
    @Autowired private AopTestFixtures.OrderingFixtureBean fixture;

    @BeforeEach
    void authenticateAndSlowRecorder() throws InterruptedException {
        var principal = new UserPrincipal(UUID.randomUUID(), UUID.randomUUID(), Role.ADMIN);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        doAnswer(invocation -> {
            Thread.sleep(30);
            return null;
        }).when(recorder).record(any(), any(), any(), any(), any(), any(), any());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bothAnnotations_timingWrapsAudit() {
        fixture.both();

        double recordedMillis = registry.get("qualityops.slow_op")
            .tag("op", "test.ordered.op").timer().totalTime(TimeUnit.MILLISECONDS);
        assertThat(recordedMillis).isGreaterThanOrEqualTo(25.0);
    }
}
