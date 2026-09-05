package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.application.port.out.AnalyticsRepository;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.DayDurationRow;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.DayRunRow;
import com.qualityops.api.result.application.service.AnalyticsService;
import com.qualityops.api.result.dto.TrendPoint;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §2 — daily run counts + duration aggregates, and Java zero-fill. */
class AnalyticsTrendsIT extends AbstractPostgresIT {

    @Autowired private AnalyticsRepository repo;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private JdbcTemplate jdbc;

    private static final Instant SINCE = LocalDate.now(ZoneOffset.UTC)
        .minusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant();

    @Test
    void runCountsByDay_runsAcross3Days_returns3Rows() {
        var ctx = new Ctx();
        run(ctx, "FAILED", 2);
        run(ctx, "PASSED", 1);
        run(ctx, "PASSED", 0);
        run(ctx, "FAILED", 0);
        run(ctx, "PASSED", 0);

        List<DayRunRow> rows = repo.runCountsByDay(ctx.orgId, ctx.projectId, SINCE);

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).totalRuns()).isEqualTo(1);
        assertThat(rows.get(0).failedRuns()).isEqualTo(1);
        assertThat(rows.get(2).totalRuns()).isEqualTo(3);
        assertThat(rows.get(2).passedRuns()).isEqualTo(2);
        assertThat(rows.get(2).failedRuns()).isEqualTo(1);
    }

    @Test
    void trends_missingDay_zeroFilledByService() {
        var ctx = new Ctx();
        run(ctx, "PASSED", 2);
        run(ctx, "PASSED", 0);

        var response = analyticsService.getTrends(ctx.projectId, ctx.orgId, 3);

        assertThat(response.points()).hasSize(3);
        TrendPoint middle = response.points().get(1);
        assertThat(middle.date()).isEqualTo(LocalDate.now(ZoneOffset.UTC).minusDays(1));
        assertThat(middle.totalRuns()).isZero();
        assertThat(middle.passedRuns()).isZero();
        assertThat(middle.failedRuns()).isZero();
        assertThat(middle.avgDurationMs()).isEqualTo(0.0);
        assertThat(middle.p95DurationMs()).isEqualTo(0.0);
    }

    @Test
    void durationByRunDay_avgAndP95_matchHandComputed() {
        var ctx = new Ctx();
        var runId = ItFixtures.insertRun(jdbc, ctx.orgId, ctx.projectId, ctx.suiteId, ctx.envId,
            ctx.userId, "PASSED", dayAt(0));
        var cases = ItFixtures.insertCases(jdbc, ctx.orgId, ctx.suiteId, 4);
        int[] durations = {100, 200, 300, 400};
        for (int i = 0; i < 4; i++) {
            ItFixtures.insertResult(jdbc, ctx.orgId, runId, cases.get(i), "PASSED", durations[i], dayAt(0));
        }

        List<DayDurationRow> rows = repo.durationByRunDay(ctx.orgId, ctx.projectId, SINCE);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).avgMs()).isEqualTo(250.0);
        assertThat(rows.get(0).p95Ms()).isCloseTo(385.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void runCountsByDay_orgIsolation() {
        var ctxA = new Ctx();
        run(ctxA, "PASSED", 0);

        var ctxB = new Ctx();
        run(ctxB, "PASSED", 0);

        List<DayRunRow> rows = repo.runCountsByDay(ctxA.orgId, ctxA.projectId, SINCE);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).totalRuns()).isEqualTo(1);
    }

    private static Instant dayAt(int daysAgo) {
        return LocalDate.now(ZoneOffset.UTC).minusDays(daysAgo).atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    private void run(Ctx ctx, String status, int daysAgo) {
        ItFixtures.insertRun(jdbc, ctx.orgId, ctx.projectId, ctx.suiteId, ctx.envId, ctx.userId,
            status, dayAt(daysAgo));
    }

    private final class Ctx {
        final UUID orgId = ItFixtures.insertOrg(jdbc);
        final UUID projectId = ItFixtures.insertProject(jdbc, orgId);
        final UUID suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        final UUID envId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        final UUID userId = ItFixtures.insertUser(jdbc, orgId);
    }
}
