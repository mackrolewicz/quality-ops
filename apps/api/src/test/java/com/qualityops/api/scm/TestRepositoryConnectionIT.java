package com.qualityops.api.scm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qualityops.api.config.JwtService;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.support.ItFixtures;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §11 — the outbound "test connection" probe: MockWebServer stands in
 *  for GitHub, the {@code @RateLimited} 3rd call in the window is rejected, and
 *  each call leaves exactly one org-scoped {@code audit_log} row. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.kafka.admin.auto-create=false",
    "qualityops.artifacts.enabled=false",
    "qualityops.scheduling.jobs-enabled=false",
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=true",
    "qualityops.ratelimit.scm-test.limit=2"
})
@Testcontainers
class TestRepositoryConnectionIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static final MockWebServer GITHUB = new MockWebServer();

    static {
        POSTGRES.start();
        REDIS.start();
        GITHUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"default_branch\":\"main\"}");
            }
        });
        try {
            GITHUB.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @AfterAll
    static void shutdown() throws IOException {
        GITHUB.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("qualityops.repo-exec.scm.github-api-base",
            () -> "http://" + GITHUB.getHostName() + ":" + GITHUB.getPort());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgA;
    private UUID userA;
    private UUID connectionId;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        var projectA = ItFixtures.insertProject(jdbc, orgA);
        connectionId = jdbc.queryForObject(
            "INSERT INTO repository_connection (org_id, project_id, provider, host, owner_path, repo_name, "
                + "default_ref, created_by) VALUES (?, ?, 'GITHUB', 'github.com', 'acme', 'web', 'main', ?) "
                + "RETURNING id",
            UUID.class, orgA, projectA, userA);
        jdbc.update("DELETE FROM audit_log WHERE org_id = ?", orgA);
    }

    @Test
    void test_probesProviderAndReturnsDefaultBranch() throws Exception {
        var resp = testCall();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = data(resp);
        assertThat(data.get("ok")).isEqualTo(Boolean.TRUE);
        assertThat(data.get("defaultBranch")).isEqualTo("main");
    }

    @Test
    void test_writesExactlyOneOrgScopedAuditRow() {
        testCall();

        Integer rows = jdbc.queryForObject(
            "SELECT count(*) FROM audit_log WHERE org_id = ? AND action = 'scm.connection.test'",
            Integer.class, orgA);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void test_thirdCallInWindow_isRateLimited() {
        assertThat(testCall().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(testCall().getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(testCall().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private ResponseEntity<String> testCall() {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userA, orgA, Role.ADMIN));
        return rest.exchange("http://localhost:" + port + "/api/v1/repository-connections/" + connectionId
            + "/test", HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<String> resp) throws Exception {
        return (Map<String, Object>) JSON.readValue(resp.getBody(), Map.class).get("data");
    }
}
