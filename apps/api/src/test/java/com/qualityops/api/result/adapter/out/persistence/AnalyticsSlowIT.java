package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.application.port.out.AnalyticsRepository;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.SlowRow;
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

/** ADR-008 §2 — top-N slowest tests by p95 {@code duration_ms}. */
class AnalyticsSlowIT extends AbstractPostgresIT {

    @Autowired private AnalyticsRepository repo;
    @Autowired private JdbcTemplate jdbc;

    private static final Instant SINCE = LocalDate.now(ZoneOffset.UTC)
        .minusDays(10).atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant TODAY = LocalDate.now(ZoneOffset.UTC)
        .atTime(12, 0).toInstant(ZoneOffset.UTC);

    @Test
    void slowByP95_fiveCasesKnownDurations_returnsTop3ByP95() {
        var ctx = new Ctx();
        var cases = ItFixtures.insertCases(jdbc, ctx.orgId, ctx.suiteId, 5);
        for (int run = 0; run < 3; run++) {
            var runId = newRun(ctx);
            for (int c = 0; c < 5; c++) {
                ItFixtures.insertResult(jdbc, ctx.orgId, runId, cases.get(c), "PASSED",
                    (c + 1) * 100, TODAY);
            }
        }

        List<SlowRow> rows = repo.slowByP95(ctx.orgId, ctx.projectId, SINCE, 3, 3);

        assertThat(rows).extracting(SlowRow::testCaseId)
            .containsExactly(cases.get(4), cases.get(3), cases.get(2));
    }

    @Test
    void slowByP95_belowMinSamples_excluded() {
        var ctx = new Ctx();
        var cases = ItFixtures.insertCases(jdbc, ctx.orgId, ctx.suiteId, 2);
        seedSamples(ctx, cases.get(0), 3);
        seedSamples(ctx, cases.get(1), 2);

        List<SlowRow> rows = repo.slowByP95(ctx.orgId, ctx.projectId, SINCE, 20, 3);

        assertThat(rows).extracting(SlowRow::testCaseId).containsExactly(cases.get(0));
    }

    @Test
    void slowByP95_orgIsolation() {
        var ctxA = new Ctx();
        var caseA = ItFixtures.insertCases(jdbc, ctxA.orgId, ctxA.suiteId, 1).get(0);
        seedSamples(ctxA, caseA, 3);

        var ctxB = new Ctx();
        var caseB = ItFixtures.insertCases(jdbc, ctxB.orgId, ctxB.suiteId, 1).get(0);
        seedSamples(ctxB, caseB, 3);

        List<SlowRow> rows = repo.slowByP95(ctxA.orgId, ctxA.projectId, SINCE, 20, 3);

        assertThat(rows).extracting(SlowRow::testCaseId).containsExactly(caseA);
    }

    private void seedSamples(Ctx ctx, UUID caseId, int count) {
        for (int i = 0; i < count; i++) {
            ItFixtures.insertResult(jdbc, ctx.orgId, newRun(ctx), caseId, "PASSED", 250 + i, TODAY);
        }
    }

    private UUID newRun(Ctx ctx) {
        return ItFixtures.insertRun(jdbc, ctx.orgId, ctx.projectId, ctx.suiteId, ctx.envId,
            ctx.userId, "PASSED", TODAY);
    }

    private final class Ctx {
        final UUID orgId = ItFixtures.insertOrg(jdbc);
        final UUID projectId = ItFixtures.insertProject(jdbc, orgId);
        final UUID suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
        final UUID envId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
        final UUID userId = ItFixtures.insertUser(jdbc, orgId);
    }
}
