package com.qualityops.api.environment.adapter.in.web;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §3 — {@code GET /api/v1/environments/{id}/health}: RBAC + org isolation. */
@AutoConfigureMockMvc
class EnvironmentHealthControllerIT extends AbstractPostgresIT {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgA;
    private UUID userA;
    private UUID projectA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        projectA = ItFixtures.insertProject(jdbc, orgA);
    }

    private String token(UUID userId, UUID orgId, Role role) {
        return "Bearer " + jwt.generateAccessToken(userId, orgId, role);
    }

    @Test
    void getHealth_ownEnvironment_returns200WithStatusAndHistory() throws Exception {
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", "https://staging.example.com");
        jdbc.update("UPDATE environments SET health_status = 'HEALTHY', last_probe_at = now(), "
            + "last_healthy_at = now(), consecutive_failures = 0 WHERE id = ?", env);
        jdbc.update("INSERT INTO environment_health_check "
            + "(org_id, environment_id, project_id, checked_at, health_status, http_status, latency_ms) "
            + "VALUES (?, ?, ?, now(), 'HEALTHY', 200, 15)", orgA, env, projectA);

        mvc.perform(get("/api/v1/environments/" + env + "/health")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.environmentId").value(env.toString()))
            .andExpect(jsonPath("$.data.healthStatus").value("HEALTHY"))
            .andExpect(jsonPath("$.data.recentChecks.length()").value(1))
            .andExpect(jsonPath("$.data.recentChecks[0].httpStatus").value(200));
    }

    @Test
    void getHealth_environmentInAnotherOrg_returns404() throws Exception {
        UUID orgB = ItFixtures.insertOrg(jdbc);
        UUID projectB = ItFixtures.insertProject(jdbc, orgB);
        UUID envB = ItFixtures.insertEnvironment(jdbc, orgB, projectB, "STAGING", "https://staging.example.com");

        mvc.perform(get("/api/v1/environments/" + envB + "/health")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isNotFound());
    }

    @Test
    void getHealth_viewerRole_isAllowed() throws Exception {
        UUID env = ItFixtures.insertEnvironment(jdbc, orgA, projectA, "STAGING", "https://staging.example.com");

        mvc.perform(get("/api/v1/environments/" + env + "/health")
                .header("Authorization", token(userA, orgA, Role.VIEWER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.healthStatus").value("UNKNOWN"));
    }
}
