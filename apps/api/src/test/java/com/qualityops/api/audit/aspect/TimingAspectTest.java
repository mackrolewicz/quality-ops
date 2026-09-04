package com.qualityops.api.audit.aspect;

import com.qualityops.api.audit.support.AopTestFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §7 — {@code TimingAspect}: always records the timer; only over-threshold
 *  calls bump {@code qualityops.slow_op.exceeded}. */
@SpringBootTest(classes = TimingAspectTest.Config.class)
class TimingAspectTest {

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
            return new TimingAspect(registry, new com.qualityops.api.config.TimingProperties(50));
        }
    }

    @Autowired private MeterRegistry registry;
    @Autowired private AopTestFixtures.TimingFixtureBean fixture;

    @Test
    void fastCall_recordsTimer_noExceededCounter() {
        fixture.fast();

        assertThat(registry.get("qualityops.slow_op").tag("op", "test.fast.op").timer().count())
            .isEqualTo(1);
        assertThat(registry.find("qualityops.slow_op.exceeded").tag("op", "test.fast.op").counter())
            .isNull();
    }

    @Test
    void slowCall_incrementsExceeded_andWarns() throws InterruptedException {
        fixture.slow();

        assertThat(registry.get("qualityops.slow_op").tag("op", "test.slow.op").timer().count())
            .isEqualTo(1);
        assertThat(registry.get("qualityops.slow_op.exceeded").tag("op", "test.slow.op")
            .counter().count()).isEqualTo(1.0);
    }
}
