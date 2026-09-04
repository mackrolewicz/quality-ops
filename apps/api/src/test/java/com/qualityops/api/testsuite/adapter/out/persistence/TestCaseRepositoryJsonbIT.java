package com.qualityops.api.testsuite.adapter.out.persistence;

import com.qualityops.api.support.AbstractPostgresIT;
import com.qualityops.api.support.ItFixtures;
import com.qualityops.api.testsuite.application.port.out.TestCaseRepository;
import com.qualityops.api.testsuite.domain.ApiRequestSpec;
import com.qualityops.api.testsuite.domain.BrowserTestSpec;
import com.qualityops.api.testsuite.domain.RepoTestSpec;
import com.qualityops.api.testsuite.domain.TestCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JPA adapter behind {@code TestCaseRepository} against a real
 * PostgreSQL: the optional {@code api_request} spec round-trips through the
 * jsonb column, a null spec stores SQL NULL, and lookups are tenant-scoped.
 */
class TestCaseRepositoryJsonbIT extends AbstractPostgresIT {

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID suiteId;

    @BeforeEach
    void seed() {
        orgId = ItFixtures.insertOrg(jdbc);
        var projectId = ItFixtures.insertProject(jdbc, orgId);
        suiteId = ItFixtures.insertSuite(jdbc, orgId, projectId);
    }

    @Test
    void save_withApiRequest_roundTripsSpec() {
        var input = caseWithSpec(sampleSpec());

        var saved = testCaseRepository.save(input);
        var loaded = testCaseRepository.findByIdAndOrgId(saved.id(), orgId).orElseThrow();

        assertThat(loaded.apiRequest()).isEqualTo(input.apiRequest());
    }

    @Test
    void save_withApiRequest_persistsAsJsonbObject() {
        var saved = testCaseRepository.save(caseWithSpec(sampleSpec()));

        String jsonType = jdbc.queryForObject(
            "SELECT jsonb_typeof(api_request) FROM test_cases WHERE id = ?", String.class, saved.id());
        String pgType = jdbc.queryForObject(
            "SELECT pg_typeof(api_request)::text FROM test_cases WHERE id = ?", String.class, saved.id());

        assertThat(jsonType).isEqualTo("object");
        assertThat(pgType).isEqualTo("jsonb");
    }

    @Test
    void save_withNullApiRequest_storesSqlNull() {
        var saved = testCaseRepository.save(caseWithSpec(null));

        Boolean isNull = jdbc.queryForObject(
            "SELECT api_request IS NULL FROM test_cases WHERE id = ?", Boolean.class, saved.id());

        assertThat(isNull).isTrue();
    }

    @Test
    void findByIdAndOrgId_foreignOrg_returnsEmpty() {
        var saved = testCaseRepository.save(caseWithSpec(sampleSpec()));
        var foreignOrgId = ItFixtures.insertOrg(jdbc);

        assertThat(testCaseRepository.findByIdAndOrgId(saved.id(), foreignOrgId)).isEmpty();
    }

    @Test
    void save_withBrowserTest_roundTripsSpec() {
        var input = caseWithBrowserSpec(sampleBrowserSpec());

        var saved = testCaseRepository.save(input);
        var loaded = testCaseRepository.findByIdAndOrgId(saved.id(), orgId).orElseThrow();

        assertThat(loaded.browserTest()).isEqualTo(input.browserTest());
    }

    @Test
    void save_withBrowserTest_persistsAsJsonbObject() {
        var saved = testCaseRepository.save(caseWithBrowserSpec(sampleBrowserSpec()));

        String jsonType = jdbc.queryForObject(
            "SELECT jsonb_typeof(browser_test) FROM test_cases WHERE id = ?", String.class, saved.id());
        String pgType = jdbc.queryForObject(
            "SELECT pg_typeof(browser_test)::text FROM test_cases WHERE id = ?", String.class, saved.id());

        assertThat(jsonType).isEqualTo("object");
        assertThat(pgType).isEqualTo("jsonb");
    }

    @Test
    void save_withNullBrowserTest_storesSqlNull() {
        var saved = testCaseRepository.save(caseWithBrowserSpec(null));

        Boolean isNull = jdbc.queryForObject(
            "SELECT browser_test IS NULL FROM test_cases WHERE id = ?", Boolean.class, saved.id());

        assertThat(isNull).isTrue();
    }

    @Test
    void save_withRepoTest_roundTripsSpec() {
        var input = caseWithRepoSpec(sampleRepoSpec(UUID.randomUUID()));

        var saved = testCaseRepository.save(input);
        var loaded = testCaseRepository.findByIdAndOrgId(saved.id(), orgId).orElseThrow();

        assertThat(loaded.repoTest()).isEqualTo(input.repoTest());
    }

    @Test
    void save_withRepoTest_persistsAsJsonbObject() {
        var saved = testCaseRepository.save(caseWithRepoSpec(sampleRepoSpec(UUID.randomUUID())));

        String jsonType = jdbc.queryForObject(
            "SELECT jsonb_typeof(repo_test) FROM test_cases WHERE id = ?", String.class, saved.id());
        String pgType = jdbc.queryForObject(
            "SELECT pg_typeof(repo_test)::text FROM test_cases WHERE id = ?", String.class, saved.id());

        assertThat(jsonType).isEqualTo("object");
        assertThat(pgType).isEqualTo("jsonb");
    }

    @Test
    void save_withNullRepoTest_storesSqlNull() {
        var saved = testCaseRepository.save(caseWithRepoSpec(null));

        Boolean isNull = jdbc.queryForObject(
            "SELECT repo_test IS NULL FROM test_cases WHERE id = ?", Boolean.class, saved.id());

        assertThat(isNull).isTrue();
    }

    @Test
    void countReferencingConnection_countsOnlyNonDeletedCasesInOrgReferencingTheConnection() {
        var connectionId = UUID.randomUUID();
        var other = UUID.randomUUID();
        testCaseRepository.save(caseWithRepoSpec(sampleRepoSpec(connectionId)));
        testCaseRepository.save(caseWithRepoSpec(sampleRepoSpec(connectionId)));
        testCaseRepository.save(caseWithRepoSpec(sampleRepoSpec(other)));
        var deleted = testCaseRepository.save(caseWithRepoSpec(sampleRepoSpec(connectionId)));
        jdbc.update("UPDATE test_cases SET deleted_at = now() WHERE id = ?", deleted.id());

        assertThat(testCaseRepository.countReferencingConnection(orgId, connectionId)).isEqualTo(2);
        assertThat(testCaseRepository.countReferencingConnection(ItFixtures.insertOrg(jdbc), connectionId))
            .isZero();
    }

    private TestCase caseWithSpec(ApiRequestSpec spec) {
        var now = Instant.now();
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Case", "desc", 0, spec, null, now, now, null);
    }

    private TestCase caseWithBrowserSpec(BrowserTestSpec spec) {
        var now = Instant.now();
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Case", "desc", 0, null, spec, now, now, null);
    }

    private TestCase caseWithRepoSpec(RepoTestSpec spec) {
        var now = Instant.now();
        return new TestCase(UUID.randomUUID(), orgId, suiteId, "Case", "desc", 0, null, null, spec,
            now, now, null);
    }

    private static RepoTestSpec sampleRepoSpec(UUID connectionId) {
        return new RepoTestSpec(connectionId, "main", "PYTEST", "svc",
            List.of("pytest", "--junitxml=report.xml"), "JUNIT_XML",
            List.of("report.xml"), List.of("artifacts/**"),
            List.of(new RepoTestSpec.EnvVarSpec("CI_NAME", "qualityops")),
            List.of(new RepoTestSpec.SecretVarSpec("REGISTRY_TOKEN", "REGISTRY_PAT")),
            "SMALL", "ISOLATED", 600);
    }

    private static ApiRequestSpec sampleSpec() {
        return new ApiRequestSpec("POST", "https://api.example.test/login",
            List.of(new ApiRequestSpec.HeaderPair("Accept", "application/json")),
            "{\"u\":1}", 200, 5000, 65536L,
            List.of(new ApiRequestSpec.ApiAssertionSpec("STATUS_EQUALS", "", "200")));
    }

    private static BrowserTestSpec sampleBrowserSpec() {
        return new BrowserTestSpec("https://app.example.test/login",
            List.of(new BrowserTestSpec.BrowserStepSpec("NAVIGATE", null, "https://app.example.test/login", null),
                    new BrowserTestSpec.BrowserStepSpec("CLICK",
                        new BrowserTestSpec.SelectorSpec("ROLE", null, "button", "Go"), null, null)),
            List.of(new BrowserTestSpec.BrowserAssertionSpec("URL_CONTAINS", null, "/home")),
            60000, 15000, 30000);
    }
}
