package com.qualityops.api.execution.adapter.in.web;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.AbstractRedisPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §6 — the CI path ({@code ci.run}, {@code limit = 1}) is gated
 *  independently of {@code run.trigger}. */
@AutoConfigureMockMvc
class CiRunRateLimitIT extends AbstractRedisPostgresIT {

    @DynamicPropertySource
    static void tightLimit(DynamicPropertyRegistry registry) {
        registry.add("qualityops.ratelimit.ci-run.limit", () -> 1);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

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

    private String token() {
        return "Bearer " + jwt.generateAccessToken(userA, orgA, Role.ADMIN);
    }

    private String body() {
        return "{\"projectId\":\"" + projectA + "\",\"suiteId\":\"" + suiteA
            + "\",\"environmentId\":\"" + envA + "\"}";
    }

    @Test
    void postCiRuns_secondCall_returns429_runTriggerStillWorks() throws Exception {
        mvc.perform(post("/api/v1/ci/runs").header("Authorization", token())
                .header("Idempotency-Key", "ci-key-1")
                .contentType("application/json").content(body()))
            .andExpect(status().isOk());

        // run.trigger has its own (default) budget — unaffected by the ci.run limit.
        mvc.perform(post("/api/v1/runs").header("Authorization", token())
                .contentType("application/json").content(body()))
            .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/ci/runs").header("Authorization", token())
                .header("Idempotency-Key", "ci-key-2")
                .contentType("application/json").content(body()))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
