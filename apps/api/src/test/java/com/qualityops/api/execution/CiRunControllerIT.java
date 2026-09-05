package com.qualityops.api.execution;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.ItFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-007 §5 — idempotent {@code POST /api/v1/ci/runs}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.listener.auto-startup=false",
    "qualityops.artifacts.enabled=false",
    "qualityops.scheduling.jobs-enabled=false",
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=false"
})
@Testcontainers
class CiRunControllerIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

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
        ItFixtures.insertCases(jdbc, orgA, suiteA, 1);
    }

    @Test
    void firstCall_returns200_andPersistsMapping() {
        var resp = submit(orgA, userA, Role.ADMIN, "gh-1-1", body(suiteA, null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String runId = dataId(resp);
        assertThat(runId).isNotNull();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ci_idempotency_key WHERE org_id=? AND idempotency_key=?",
            Integer.class, orgA, "gh-1-1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status::text FROM test_runs WHERE id=?::uuid",
            String.class, runId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT queue_state FROM run_queue WHERE run_id=?::uuid",
            String.class, runId)).isEqualTo("QUEUED");
    }

    @Test
    void sameKeySameBody_returnsSameRun_200() {
        var first = submit(orgA, userA, Role.ADMIN, "gh-2-1", body(suiteA, null));
        var second = submit(orgA, userA, Role.ADMIN, "gh-2-1", body(suiteA, null));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dataId(second)).isEqualTo(dataId(first));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE suite_id=?",
            Integer.class, suiteA)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_returns409_idempotencyConflict() {
        var otherSuite = ItFixtures.insertSuite(jdbc, orgA, projectA);
        submit(orgA, userA, Role.ADMIN, "gh-3-1", body(suiteA, null));

        var conflict = submit(orgA, userA, Role.ADMIN, "gh-3-1", body(otherSuite, null));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).contains("IDEMPOTENCY_KEY_CONFLICT");
    }

    @Test
    void missingIdempotencyKey_returns400() {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userA, orgA, Role.ADMIN));
        headers.add("Content-Type", "application/json");
        var resp = rest.exchange("http://localhost:" + port + "/api/v1/ci/runs", HttpMethod.POST,
            new HttpEntity<>(body(suiteA, null), headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blankIdempotencyKey_returns400() {
        assertThat(submit(orgA, userA, Role.ADMIN, "", body(suiteA, null)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void oversizeIdempotencyKey_returns400() {
        assertThat(submit(orgA, userA, Role.ADMIN, "x".repeat(201), body(suiteA, null)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void twoConcurrentFirstCalls_sameKey_produceOneRun() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        var idOne = new AtomicReference<String>();
        var idTwo = new AtomicReference<String>();
        pool.submit(() -> race(start, idOne));
        pool.submit(() -> race(start, idTwo));
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(idOne.get()).isNotNull().isEqualTo(idTwo.get());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test_runs WHERE suite_id=?",
            Integer.class, suiteA)).isEqualTo(1);
    }

    private void race(CountDownLatch start, AtomicReference<String> sink) {
        await(start);
        var r = submit(orgA, userA, Role.ADMIN, "race-1", body(suiteA, null));
        if (r.getStatusCode() == HttpStatus.OK) {
            sink.set(dataId(r));
        }
    }

    @Test
    void orgAKey_andOrgBSameKey_areIndependent() {
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);
        var projectB = ItFixtures.insertProject(jdbc, orgB);
        var suiteB = ItFixtures.insertSuite(jdbc, orgB, projectB);
        var envB = ItFixtures.insertEnvironment(jdbc, orgB, projectB);
        ItFixtures.insertCases(jdbc, orgB, suiteB, 1);

        var a = submit(orgA, userA, Role.ADMIN, "shared-key", body(suiteA, null));
        var b = submit(orgB, userB, Role.ADMIN, "shared-key",
            Map.of("projectId", projectB.toString(), "suiteId", suiteB.toString(),
                "environmentId", envB.toString()));

        assertThat(dataId(a)).isNotEqualTo(dataId(b));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ci_idempotency_key WHERE idempotency_key='shared-key'",
            Integer.class)).isEqualTo(2);
    }

    @Test
    void orgB_cannotGetOrgAsRun_404() {
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);
        String runId = dataId(submit(orgA, userA, Role.ADMIN, "gh-9-1", body(suiteA, null)));

        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userB, orgB, Role.ADMIN));
        var resp = rest.exchange("http://localhost:" + port + "/api/v1/runs/" + runId,
            HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewerRole_forbidden_403() {
        assertThat(submit(orgA, userA, Role.VIEWER, "viewer-1", body(suiteA, null)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberRequestingHighPriority_403() {
        assertThat(submit(orgA, userA, Role.MEMBER, "member-high-1", body(suiteA, "HIGH")).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private Map<String, Object> body(UUID suiteId, String priority) {
        var m = new java.util.HashMap<String, Object>();
        m.put("projectId", projectA.toString());
        m.put("suiteId", suiteId.toString());
        m.put("environmentId", envA.toString());
        if (priority != null) {
            m.put("priority", priority);
        }
        return m;
    }

    private org.springframework.http.ResponseEntity<String> submit(UUID orgId, UUID userId, Role role,
                                                                   String key, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userId, orgId, role));
        headers.add("Content-Type", "application/json");
        headers.add("Idempotency-Key", key);
        return rest.exchange("http://localhost:" + port + "/api/v1/ci/runs", HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private static String dataId(org.springframework.http.ResponseEntity<String> resp) {
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resp.getBody(), Map.class);
            var data = (Map<String, Object>) node.get("data");
            return data == null ? null : (String) data.get("id");
        } catch (Exception e) {
            throw new IllegalStateException("bad body: " + resp.getBody(), e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
