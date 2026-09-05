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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §6 — {@code @RateLimited} on {@code POST /api/v1/runs}, {@code limit = 2}. */
@AutoConfigureMockMvc
class RunTriggerRateLimitIT extends AbstractRedisPostgresIT {

    @DynamicPropertySource
    static void tightLimit(DynamicPropertyRegistry registry) {
        registry.add("qualityops.ratelimit.run-trigger.limit", () -> 2);
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

    private org.springframework.test.web.servlet.ResultActions trigger(UUID org, UUID user,
                                                                       UUID project, UUID suite,
                                                                       UUID env) throws Exception {
        String body = "{\"projectId\":\"" + project + "\",\"suiteId\":\"" + suite
            + "\",\"environmentId\":\"" + env + "\"}";
        return mvc.perform(post("/api/v1/runs")
            .header("Authorization", "Bearer " + jwt.generateAccessToken(user, org, Role.ADMIN))
            .contentType("application/json").content(body));
    }

    @Test
    void postRuns_thirdCallInWindow_returns429WithRetryAfterAndZeroRemaining() throws Exception {
        trigger(orgA, userA, projectA, suiteA, envA).andExpect(status().isCreated());
        trigger(orgA, userA, projectA, suiteA, envA).andExpect(status().isCreated());

        trigger(orgA, userA, projectA, suiteA, envA)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
            .andExpect(header().exists("Retry-After"))
            .andExpect(header().string("X-RateLimit-Remaining", "0"));
    }

    @Test
    void postRuns_orgBUnaffectedByOrgALimit() throws Exception {
        trigger(orgA, userA, projectA, suiteA, envA).andExpect(status().isCreated());
        trigger(orgA, userA, projectA, suiteA, envA).andExpect(status().isCreated());
        trigger(orgA, userA, projectA, suiteA, envA).andExpect(status().isTooManyRequests());

        UUID orgB = ItFixtures.insertOrg(jdbc);
        UUID userB = ItFixtures.insertUser(jdbc, orgB);
        UUID projectB = ItFixtures.insertProject(jdbc, orgB);
        UUID suiteB = ItFixtures.insertSuite(jdbc, orgB, projectB);
        UUID envB = ItFixtures.insertEnvironment(jdbc, orgB, projectB);
        ItFixtures.insertCases(jdbc, orgB, suiteB, 1);

        trigger(orgB, userB, projectB, suiteB, envB).andExpect(status().isCreated());
    }

    @Test
    void postRuns_underLimit_carriesRateLimitHeaders() throws Exception {
        trigger(orgA, userA, projectA, suiteA, envA)
            .andExpect(status().isCreated())
            .andExpect(header().string("X-RateLimit-Limit", "2"));
    }
}
