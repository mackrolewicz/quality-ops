package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.result.application.port.out.AnalyticsRepository;
import com.qualityops.api.result.application.port.out.AnalyticsRepository.FlakyRow;
import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-008 §1 — windowed flaky scan over {@code test_results} + {@code test_runs}. */
class AnalyticsRepositoryIT extends AbstractPostgresIT {

    @Autowired private AnalyticsRepository repo;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void flakyWindow_moreResultsThanWindow_truncatesAtWindow() {
        var org = ItFixtures.insertOrg(jdbc);
        var ctx = new Ctx(org);
        var caseId = ItFixtures.insertCases(jdbc, org, ctx.suiteId, 1).get(0);
        seed(ctx, caseId, alternating(30));

        var row = one(repo.flakyWindow(org, ctx.projectId, 20, 5), caseId);

        assertThat(row.runsAnalyzed()).isEqualTo(20);
    }

    @Test
    void flakyWindow_belowMinRuns_omitsCase() {
        var org = ItFixtures.insertOrg(jdbc);
        var ctx = new Ctx(org);
        var caseId = ItFixtures.insertCases(jdbc, org, ctx.suiteId, 1).get(0);
        seed(ctx, caseId, alternating(4));

        var rows = repo.flakyWindow(org, ctx.projectId, 20, 5);

        assertThat(rows).noneMatch(r -> r.testCaseId().equals(caseId));
    }

    @Test
    void flakyWindow_alternatingStatuses_countsTransitions() {
        var org = ItFixtures.insertOrg(jdbc);
        var ctx = new Ctx(org);
        var caseId = ItFixtures.insertCases(jdbc, org, ctx.suiteId, 1).get(0);
        seed(ctx, caseId, alternating(6));

        var row = one(repo.flakyWindow(org, ctx.projectId, 20, 5), caseId);

        assertThat(row.transitions()).isEqualTo(5);
    }

    @Test
    void flakyWindow_orgB_neverAppearsInOrgAReport() {
        var orgA = ItFixtures.insertOrg(jdbc);
        var ctxA = new Ctx(orgA);
        var caseA = ItFixtures.insertCases(jdbc, orgA, ctxA.suiteId, 1).get(0);
        seed(ctxA, caseA, alternating(8));

        var orgB = ItFixtures.insertOrg(jdbc);
        var ctxB = new Ctx(orgB);
        var caseB = ItFixtures.insertCases(jdbc, orgB, ctxB.suiteId, 1).get(0);
        seed(ctxB, caseB, alternating(8));

        var rows = repo.flakyWindow(orgA, ctxA.projectId, 20, 5);

        assertThat(rows).extracting(FlakyRow::testCaseId).containsExactly(caseA).doesNotContain(caseB);
    }

    @Test
    void flakyWindow_scopedToProject() {
        var org = ItFixtures.insertOrg(jdbc);
        var ctx1 = new Ctx(org);
        var case1 = ItFixtures.insertCases(jdbc, org, ctx1.suiteId, 1).get(0);
        seed(ctx1, case1, alternating(8));

        var ctx2 = new Ctx(org);
        var case2 = ItFixtures.insertCases(jdbc, org, ctx2.suiteId, 1).get(0);
        seed(ctx2, case2, alternating(8));

        var rows = repo.flakyWindow(org, ctx1.projectId, 20, 5);

        assertThat(rows).extracting(FlakyRow::testCaseId).containsExactly(case1).doesNotContain(case2);
    }

    private static List<String> alternating(int n) {
        return java.util.stream.IntStream.range(0, n)
            .mapToObj(i -> i % 2 == 0 ? "PASSED" : "FAILED")
            .toList();
    }

    private static FlakyRow one(List<FlakyRow> rows, UUID caseId) {
        return rows.stream().filter(r -> r.testCaseId().equals(caseId)).findFirst().orElseThrow();
    }

    private void seed(Ctx ctx, UUID caseId, List<String> statuses) {
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < statuses.size(); i++) {
            Instant at = base.plusSeconds(i);
            var runId = ItFixtures.insertRun(jdbc, ctx.orgId, ctx.projectId, ctx.suiteId,
                ctx.envId, ctx.userId, "PASSED", at);
            ItFixtures.insertResult(jdbc, ctx.orgId, runId, caseId, statuses.get(i), 100 + i, at);
        }
    }

    /** One project graph in an org. */
    private final class Ctx {
        final UUID orgId;
        final UUID projectId;
        final UUID suiteId;
        final UUID envId;
        final UUID userId;

        Ctx(UUID orgId) {
            this.orgId = orgId;
            this.projectId = ItFixtures.insertProject(jdbc, orgId);
            this.suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
            this.envId = ItFixtures.insertEnvironment(jdbc, orgId, projectId);
            this.userId = ItFixtures.insertUser(jdbc, orgId);
        }
    }
}
