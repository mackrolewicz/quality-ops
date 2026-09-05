package com.qualityops.api.execution.application.service;

import com.qualityops.api.audit.application.port.out.AuditLogRepository;
import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADR-008 §7 — {@code @Audited} on {@code OrgConcurrencyService.set} promotes the
 *  concurrency-change to a durable {@code audit_log} row; a failed audit write
 *  never rolls back the business change (REQUIRES_NEW + swallow). */
@AutoConfigureMockMvc
class OrgConcurrencyAuditIT extends AbstractPostgresIT {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private AuditLogRepository auditLogRepository;

    private UUID orgA;
    private UUID ownerA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        ownerA = ItFixtures.insertUser(jdbc, orgA);
    }

    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM org_run_concurrency WHERE org_id = ?", orgA);
        jdbc.update("DELETE FROM audit_log WHERE org_id = ?", orgA);
    }

    private void updateConcurrency(int value) throws Exception {
        mvc.perform(put("/api/v1/admin/orgs/" + orgA + "/run-concurrency")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(ownerA, orgA, Role.OWNER))
                .contentType("application/json").content("{\"maxActiveRuns\":" + value + "}"))
            .andExpect(status().isOk());
    }

    @Test
    void updateConcurrency_writesAuditLogRow() throws Exception {
        updateConcurrency(3);

        Integer count = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE action = 'org.run_concurrency.update' "
                + "AND org_id = ? AND actor_user_id = ? AND outcome = 'SUCCESS'",
            Integer.class, orgA, ownerA);
        assertThat(count).isEqualTo(1);

        UUID targetId = jdbc.queryForObject(
            "SELECT target_id FROM audit_log WHERE org_id = ? AND action = 'org.run_concurrency.update'",
            UUID.class, orgA);
        assertThat(targetId).isEqualTo(orgA);
    }

    @Test
    void updateConcurrency_businessTxCommits_evenIfAuditInsertFails() throws Exception {
        doThrow(new DataAccessResourceFailureException("audit store down"))
            .when(auditLogRepository).insert(any());

        updateConcurrency(9);

        Integer overrides = jdbc.queryForObject(
            "SELECT count(*) FROM org_run_concurrency WHERE org_id = ? AND max_active_runs = 9",
            Integer.class, orgA);
        assertThat(overrides).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE org_id = ?",
            Integer.class, orgA)).isZero();
    }
}
