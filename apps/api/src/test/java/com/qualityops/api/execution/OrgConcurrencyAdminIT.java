package com.qualityops.api.execution;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase;
import com.qualityops.api.execution.application.port.in.EnqueueRunUseCase.EnqueueRunCommand;
import com.qualityops.api.execution.application.port.in.GetQueueAdminSummaryUseCase;
import com.qualityops.api.execution.application.service.QueueDispatchService;
import com.qualityops.api.execution.domain.RunPriority;
import com.qualityops.api.execution.domain.RunSource;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.AbstractKafkaPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-007 §3 + §4 — org concurrency write path + queue admin summary. */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class OrgConcurrencyAdminIT extends AbstractKafkaPostgresIT {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EnqueueRunUseCase enqueueRunUseCase;
    @Autowired private QueueDispatchService queueDispatchService;
    @Autowired private GetQueueAdminSummaryUseCase getQueueAdminSummaryUseCase;

    private UUID orgA;
    private UUID ownerA;
    private UUID projectA;
    private UUID suiteA;
    private UUID envA;
    private UUID userA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        ownerA = ItFixtures.insertUser(jdbc, orgA);
        projectA = ItFixtures.insertProject(jdbc, orgA);
        suiteA = ItFixtures.insertSuite(jdbc, orgA, projectA);
        envA = ItFixtures.insertEnvironment(jdbc, orgA, projectA);
        userA = ItFixtures.insertUser(jdbc, orgA);
        ItFixtures.insertCases(jdbc, orgA, suiteA, 1);
    }

    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM run_queue WHERE queue_state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
        jdbc.update("DELETE FROM org_run_concurrency");
    }

    private String token(UUID userId, UUID orgId, Role role) {
        return "Bearer " + jwt.generateAccessToken(userId, orgId, role);
    }

    private UUID enqueue() {
        return enqueueRunUseCase.enqueue(new EnqueueRunCommand(orgA, projectA, suiteA, envA, userA,
            RunPriority.NORMAL, RunSource.MANUAL, null)).runId();
    }

    @Test
    void owner_setsOwnOrg_200_andDispatcherRespectsIt() throws Exception {
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.maxActiveRuns").value(2))
            .andExpect(jsonPath("$.data.source").value("OVERRIDE"));

        enqueue();
        enqueue();
        enqueue();
        queueDispatchService.dispatchAvailable();

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM run_queue WHERE org_id=? AND queue_state='DISPATCHED'",
            Integer.class, orgA)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM run_queue WHERE org_id=? AND queue_state='QUEUED'",
            Integer.class, orgA)).isEqualTo(1);

        var summary = getQueueAdminSummaryUseCase.summary(orgA);
        assertThat(summary.org().effectiveMaxActiveRuns()).isEqualTo(2);
        assertThat(summary.org().maxActiveRunsSource()).isEqualTo("OVERRIDE");
        assertThat(summary.org().activeRuns()).isEqualTo(2);
        assertThat(summary.org().queuedByPriority()).containsEntry("NORMAL", 1L);
        assertThat(summary.process().reaped()).isNotNull();
        assertThat(summary.process().retries()).isNotNull();

        mvc.perform(get("/api/v1/admin/queue")
                .header("Authorization", token(ownerA, orgA, Role.OWNER)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.org.effectiveMaxActiveRuns").value(2))
            .andExpect(jsonPath("$.data.org.maxActiveRunsSource").value("OVERRIDE"));
    }

    @Test
    void get_withOverride_returnsOverrideAndSource() throws Exception {
        jdbc.update("INSERT INTO org_run_concurrency (org_id, max_active_runs) VALUES (?, 7)", orgA);

        mvc.perform(get("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.maxActiveRuns").value(7))
            .andExpect(jsonPath("$.data.source").value("OVERRIDE"));
    }

    @Test
    void get_withoutOverride_returnsDefault() throws Exception {
        mvc.perform(get("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.ADMIN)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.maxActiveRuns").value(5))
            .andExpect(jsonPath("$.data.source").value("DEFAULT"));
    }

    @Test
    void set_zero_returns400() throws Exception {
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":0}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void set_overMax_returns400() throws Exception {
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":1001}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void ownerOfOrgA_settingOrgB_forbidden_403() throws Exception {
        var orgB = ItFixtures.insertOrg(jdbc);

        mvc.perform(put("/api/v1/admin/orgs/" + orgB + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":3}"))
            .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_run_concurrency WHERE org_id=?",
            Integer.class, orgB)).isZero();
    }

    @Test
    void member_forbidden_403() throws Exception {
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(userA, orgA, Role.MEMBER))
                .contentType("application/json").content("{\"maxActiveRuns\":3}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void set_emitsAuditLogLine(CapturedOutput output) throws Exception {
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":10}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", token(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":7}"))
            .andExpect(status().isOk());

        assertThat(output.getOut())
            .contains("audit action=org.run_concurrency.update actor=" + ownerA + " org=" + orgA)
            .contains("old=default:5 new=10")
            .contains("old=10 new=7");
    }
}
