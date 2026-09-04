package com.qualityops.api.config;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §4 — cache is fail-open: a dead Redis degrades latency, never correctness. */
@AutoConfigureMockMvc
class CacheFailOpenIT extends AbstractPostgresIT {

    @DynamicPropertySource
    static void deadRedis(DynamicPropertyRegistry registry) {
        registry.add("qualityops.cache.enabled", () -> true);
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

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        projectA = ItFixtures.insertProject(jdbc, orgA);
    }

    private void callFlaky() throws Exception {
        mvc.perform(get("/api/v1/analytics/flaky")
                .param("projectId", projectA.toString())
                .header("Authorization", "Bearer " + jwt.generateAccessToken(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.projectId").value(projectA.toString()));
    }

    @Test
    void getFlaky_deadRedis_returns200FromDatabase() throws Exception {
        callFlaky();
    }

    @Test
    void getFlaky_deadRedis_incrementsCacheErrorsCounter() throws Exception {
        callFlaky();

        assertThat(meterRegistry.get("qualityops.cache.errors").tag("op", "get").counter().count())
            .isGreaterThanOrEqualTo(1.0);
    }
}
