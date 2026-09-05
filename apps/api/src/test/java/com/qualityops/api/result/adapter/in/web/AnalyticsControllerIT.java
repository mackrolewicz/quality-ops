package com.qualityops.api.result.adapter.in.web;

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

/** ADR-008 §1-2 — {@code /api/v1/analytics/*}: RBAC, org check, bound clamping, 400 on missing param. */
@AutoConfigureMockMvc
class AnalyticsControllerIT extends AbstractPostgresIT {

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
    void getFlaky_missingProjectId_returns400() throws Exception {
        mvc.perform(get("/api/v1/analytics/flaky")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void getFlaky_projectInAnotherOrg_returns404() throws Exception {
        var orgB = ItFixtures.insertOrg(jdbc);
        var projectB = ItFixtures.insertProject(jdbc, orgB);

        mvc.perform(get("/api/v1/analytics/flaky")
                .param("projectId", projectB.toString())
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isNotFound());
    }

    @Test
    void getFlaky_windowBelow5_clampedTo5() throws Exception {
        mvc.perform(get("/api/v1/analytics/flaky")
                .param("projectId", projectA.toString())
                .param("window", "1")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.window").value(5));
    }

    @Test
    void getFlaky_windowAbove50_clampedTo50() throws Exception {
        mvc.perform(get("/api/v1/analytics/flaky")
                .param("projectId", projectA.toString())
                .param("window", "999")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.window").value(50));
    }

    @Test
    void getTrends_daysAbove90_clampedTo90() throws Exception {
        mvc.perform(get("/api/v1/analytics/trends")
                .param("projectId", projectA.toString())
                .param("days", "365")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days").value(90));
    }

    @Test
    void getSlow_limitAbove100_clampedTo100() throws Exception {
        mvc.perform(get("/api/v1/analytics/slow")
                .param("projectId", projectA.toString())
                .param("limit", "500")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.limit").value(100));
    }

    @Test
    void getFlaky_viewerRole_isAllowed() throws Exception {
        mvc.perform(get("/api/v1/analytics/flaky")
                .param("projectId", projectA.toString())
                .header("Authorization", token(userA, orgA, Role.VIEWER)))
            .andExpect(status().isOk());
    }
}
