package com.qualityops.api.audit.support;

import com.qualityops.api.audit.annotation.Audited;
import com.qualityops.api.audit.annotation.Timed;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.UUID;

/**
 * Shared aspect-target beans for the WP7 AOP unit tests. {@code @TestConfiguration}
 * so it is excluded from component scanning and only loads where explicitly
 * {@code @Import}ed / referenced via {@code @SpringBootTest(classes = ...)}.
 */
@TestConfiguration
public class AopTestFixtures {

    @Bean
    public AuditFixtureBean auditFixtureBean() {
        return new AuditFixtureBean();
    }

    @Bean
    public TimingFixtureBean timingFixtureBean() {
        return new TimingFixtureBean();
    }

    @Bean
    public OrderingFixtureBean orderingFixtureBean() {
        return new OrderingFixtureBean();
    }

    @Bean
    public SelfInvocationFixtureBean selfInvocationFixtureBean() {
        return new SelfInvocationFixtureBean();
    }

    /** A record with a {@code UUID id()} accessor — exercises the aspect's
     *  return-value {@code targetId} resolution. */
    public record FixtureResult(UUID id) {
    }

    public static class AuditFixtureBean {

        @Audited(action = "test.action", targetType = "thing")
        public FixtureResult annotated(UUID id) {
            return new FixtureResult(id);
        }

        @Audited(action = "test.fail", targetType = "thing")
        public void throwing() {
            throw new IllegalStateException("boom");
        }

        public void unannotated() {
            // no aspect should fire
        }
    }

    public static class TimingFixtureBean {

        @Timed(value = "test.fast.op", slowThresholdMillis = 50)
        public void fast() {
            // returns well under the threshold
        }

        @Timed(value = "test.slow.op", slowThresholdMillis = 50)
        public void slow() throws InterruptedException {
            Thread.sleep(120);
        }
    }

    public static class OrderingFixtureBean {

        @Timed(value = "test.ordered.op", slowThresholdMillis = 1)
        @Audited(action = "test.ordered", targetType = "thing")
        public void both() {
            // the audit recorder is stubbed to sleep; TimingAspect (outer) must see it
        }
    }

    public static class SelfInvocationFixtureBean {

        /** Unannotated: calls the annotated sibling via {@code this} — the proxy
         *  is bypassed so {@link #inner(UUID)}'s {@code @Audited} is skipped. */
        public void outer(UUID id) {
            inner(id);
        }

        @Audited(action = "test.self", targetType = "thing")
        public void inner(UUID id) {
            // reached directly through the proxy -> aspect fires
        }
    }
}
