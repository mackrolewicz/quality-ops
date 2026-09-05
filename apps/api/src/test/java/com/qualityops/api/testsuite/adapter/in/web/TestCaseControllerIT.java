package com.qualityops.api.testsuite.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §11 / WP4 — authoring a {@code repoTest} case through the existing
 *  suite-case endpoints: the spec persists as {@code repo_test} JSONB and is
 *  echoed back; cross-org access is denied. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false",
    "qualityops.artifacts.enabled=false",
    "qualityops.scheduling.jobs-enabled=false",
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=false"
})
@Testcontainers
class TestCaseControllerIT {

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

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgA;
    private UUID userA;
    private UUID suiteA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        var projectA = ItFixtures.insertProject(jdbc, orgA);
        suiteA = ItFixtures.insertSuite(jdbc, orgA, projectA);
    }

    private Map<String, Object> repoTestBody() {
        return Map.of(
            "repositoryConnectionId", UUID.randomUUID().toString(),
            "requestedRef", "main",
            "framework", "PYTEST",
            "command", List.of("pytest", "--junitxml=report.xml"),
            "reportFormat", "JUNIT_XML",
            "reportPaths", List.of("report.xml"),
            "secretVars", List.of(Map.of("name", "TOKEN", "secretRef", "REGISTRY_PAT")));
    }

    @Test
    void create_repoTestCase_persistsRepoTestJsonbAndEchoesSpec() throws Exception {
        var body = Map.of("name", "Repo suite", "repoTest", repoTestBody());

        var created = post("/api/v1/suites/" + suiteA + "/cases", orgA, userA, Role.ADMIN, body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = data(created).get("id");

        String jsonType = jdbc.queryForObject(
            "SELECT jsonb_typeof(repo_test) FROM test_cases WHERE id = ?::uuid", String.class, id);
        assertThat(jsonType).isEqualTo("object");

        var fetched = rest.exchange(url("/api/v1/cases/" + id), HttpMethod.GET,
            auth(orgA, userA, Role.MEMBER), String.class);
        var repoTest = asMap(data(fetched).get("repoTest"));
        assertThat(repoTest.get("framework")).isEqualTo("PYTEST");
        assertThat(repoTest.get("requestedRef")).isEqualTo("main");
        assertThat(repoTest.get("command")).isEqualTo(List.of("pytest", "--junitxml=report.xml"));
    }

    @Test
    void create_repoTestPlusApiRequest_returns400() {
        var body = Map.of("name", "Mixed", "repoTest", repoTestBody(),
            "apiRequest", Map.of("method", "GET", "url", "https://api.example.test/x"));

        assertThat(post("/api/v1/suites/" + suiteA + "/cases", orgA, userA, Role.ADMIN, body).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void get_repoTestCase_foreignOrg_returns404() throws Exception {
        var created = post("/api/v1/suites/" + suiteA + "/cases", orgA, userA, Role.ADMIN,
            Map.of("name", "Repo case", "repoTest", repoTestBody()));
        var id = data(created).get("id");
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);

        var resp = rest.exchange(url("/api/v1/cases/" + id), HttpMethod.GET,
            auth(orgB, userB, Role.ADMIN), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Void> auth(UUID orgId, UUID userId, Role role) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userId, orgId, role));
        return new HttpEntity<>(headers);
    }

    private ResponseEntity<String> post(String path, UUID orgId, UUID userId, Role role, Map<String, ?> body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userId, orgId, role));
        headers.add("Content-Type", "application/json");
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<String> resp) throws Exception {
        return (Map<String, Object>) JSON.readValue(resp.getBody(), Map.class).get("data");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
