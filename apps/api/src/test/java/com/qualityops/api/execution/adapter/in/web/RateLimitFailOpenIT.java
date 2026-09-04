package com.qualityops.api.execution.adapter.in.web;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §6 — a dead Redis fails open: the request still succeeds and
 *  {@code qualityops.ratelimit.errors} increments. */
@AutoConfigureMockMvc
class RateLimitFailOpenIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("qualityops.ratelimit.enabled", () -> true);
        registry.add("qualityops.ratelimit.fail-open", () -> true);
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> 6390);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private UUID orgA;
    private UUID userA;
    private UUID projectA;
    private UUID suiteA;
    private UUID envA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        projectA = ItFixtures.insertProject(jdbc, orgA);
        suiteA = ItFixtures.insertSuite(jdbc, orgA, projectA);
        envA = ItFixtures.insertEnvironment(jdbc, orgA, projectA);
        ItFixtures.insertCases(jdbc, orgA, suiteA, 1);
    }

    @Test
    void postRuns_deadRedis_stillSucceeds_incrementsRateLimitErrors() throws Exception {
        String body = "{\"projectId\":\"" + projectA + "\",\"suiteId\":\"" + suiteA
            + "\",\"environmentId\":\"" + envA + "\"}";

        mvc.perform(post("/api/v1/runs")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(userA, orgA, Role.ADMIN))
                .contentType("application/json").content(body))
            .andExpect(status().isCreated());

        assertThat(meterRegistry.get("qualityops.ratelimit.errors").counter().count())
            .isGreaterThanOrEqualTo(1.0);
    }
}
