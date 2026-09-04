package com.qualityops.api.config;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.execution.application.port.in.ApplyRunLifecycleUseCase;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestCaseSnapshotItem;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.result.application.port.out.AnalyticsRepository;
import com.qualityops.api.support.AbstractRedisKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §4 — Redis dashboard cache: hit-on-second-call, per-org eviction on terminal, org isolation. */
@AutoConfigureMockMvc
class DashboardCacheIT extends AbstractRedisKafkaPostgresIT {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplyRunLifecycleUseCase applyRunLifecycleUseCase;

    @SpyBean private AnalyticsRepository analyticsRepository;
    @SpyBean private RunRepository runRepository;

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
    }

    private String token(UUID userId, UUID orgId, Role role) {
        return "Bearer " + jwt.generateAccessToken(userId, orgId, role);
    }

    private void getFlaky(UUID orgId, UUID userId, UUID projectId) throws Exception {
        mvc.perform(get("/api/v1/analytics/flaky")
                .param("projectId", projectId.toString())
                .header("Authorization", token(userId, orgId, Role.MEMBER)))
            .andExpect(status().isOk());
    }

    private void getRuns(UUID orgId, UUID userId, UUID projectId, UUID suiteId) throws Exception {
        mvc.perform(get("/api/v1/runs")
                .param("projectId", projectId.toString())
                .param("suiteId", suiteId.toString())
                .header("Authorization", token(userId, orgId, Role.MEMBER)))
            .andExpect(status().isOk());
    }

    private UUID seedRunningRun() {
        var executionId = UUID.randomUUID();
        var snapshot = new RunConfigSnapshot(List.of(new TestCaseSnapshotItem(UUID.randomUUID(), "Case", 0)));
        return runRepository.save(new TestRun(UUID.randomUUID(), orgA, projectA, suiteA, envA,
            executionId, RunStatus.RUNNING, ItFixtures.insertUser(jdbc, orgA), snapshot,
            null, null, Instant.now())).id();
    }

    private void completeRun(UUID runId) {
        var executionId = runRepository.findByIdAndOrgId(runId, orgA).orElseThrow().executionId();
        applyRunLifecycleUseCase.onRunCompleted(new RunCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
            orgA, runId, executionId, Instant.now(), RunCompletedEvent.SCHEMA_VERSION, projectA, suiteA,
            RunOutcome.PASSED, List.of(), null));
    }

    @Test
    void getFlaky_secondCall_servedFromRedis_repositoryHitOnce() throws Exception {
        getFlaky(orgA, userA, projectA);
        getFlaky(orgA, userA, projectA);

        verify(analyticsRepository, times(1)).flakyWindow(eq(orgA), eq(projectA), anyInt(), anyInt());
    }

    @Test
    void runsCompleted_forOrg_evictsAnalyticsAndRunsList_nextReadHitsDb() throws Exception {
        getFlaky(orgA, userA, projectA);
        getFlaky(orgA, userA, projectA);
        verify(analyticsRepository, times(1)).flakyWindow(eq(orgA), eq(projectA), anyInt(), anyInt());

        completeRun(seedRunningRun());

        getFlaky(orgA, userA, projectA);
        verify(analyticsRepository, times(2)).flakyWindow(eq(orgA), eq(projectA), anyInt(), anyInt());
    }

    @Test
    void runsCompleted_forOrgA_leavesOrgBCacheEntryIntact() throws Exception {
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);
        var projectB = ItFixtures.insertProject(jdbc, orgB);

        getFlaky(orgA, userA, projectA);
        getFlaky(orgA, userA, projectA);
        getFlaky(orgB, userB, projectB);
        getFlaky(orgB, userB, projectB);

        completeRun(seedRunningRun());

        getFlaky(orgA, userA, projectA);
        getFlaky(orgB, userB, projectB);

        verify(analyticsRepository, times(2)).flakyWindow(eq(orgA), eq(projectA), anyInt(), anyInt());
        verify(analyticsRepository, times(1)).flakyWindow(eq(orgB), eq(projectB), anyInt(), anyInt());
    }

    @Test
    void getRuns_noFilters_returns200_andSecondCallServedFromCache() throws Exception {
        seedRunningRun();

        // All list filters null — exercises the real RunJpaRepository.findAllByOrgId
        // query against Postgres (regression lock for the untyped-enum-param concern).
        mvc.perform(get("/api/v1/runs")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs")
                .header("Authorization", token(userA, orgA, Role.MEMBER)))
            .andExpect(status().isOk());

        verify(runRepository, times(1))
            .findAllByOrgId(eq(orgA), any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void runsList_isCachedPerOrgAndFilters() throws Exception {
        // Stub the repository read: RunJpaRepository.findAllByOrgId has a pre-existing
        // enum-null-parameter defect on Postgres (out of WP2/WP3 scope) — this test
        // isolates the @Cacheable("runs.list") behaviour, not that query.
        doReturn(new PageResult<>(List.of(), 1, 20, 0L))
            .when(runRepository).findAllByOrgId(any(), any(), any(), any(), any(), anyInt(), anyInt());

        getRuns(orgA, userA, projectA, suiteA);
        getRuns(orgA, userA, projectA, suiteA);
        verify(runRepository, times(1))
            .findAllByOrgId(eq(orgA), eq(projectA), eq(suiteA), any(), any(), anyInt(), anyInt());

        var otherSuite = ItFixtures.insertSuite(jdbc, orgA, projectA);
        getRuns(orgA, userA, projectA, otherSuite);
        verify(runRepository, times(1))
            .findAllByOrgId(eq(orgA), eq(projectA), eq(otherSuite), any(), any(), anyInt(), anyInt());
    }
}
