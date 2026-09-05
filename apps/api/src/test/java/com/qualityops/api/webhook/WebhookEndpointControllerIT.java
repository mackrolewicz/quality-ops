package com.qualityops.api.webhook;

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

/** ADR-007 §6.2 — webhook endpoint CRUD; the secret is never echoed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.listener.auto-startup=false",
    "qualityops.artifacts.enabled=false",
    "qualityops.scheduling.jobs-enabled=false",
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=false"
})
@Testcontainers
class WebhookEndpointControllerIT {

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
    private static final String SECRET = "0123456789abcdef";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwt;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgA;
    private UUID userA;
    private UUID projectA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        projectA = ItFixtures.insertProject(jdbc, orgA);
    }

    @Test
    void register_returnsSecretMaskedAsSecretSet() throws Exception {
        var resp = post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://example.com/hook", "secret", SECRET, "enabled", true));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var data = data(resp);
        assertThat(data.get("secretSet")).isEqualTo(Boolean.TRUE);
        assertThat(data).doesNotContainKey("secret");
        assertThat(jdbc.queryForObject("SELECT secret FROM webhook_endpoint WHERE id=?::uuid",
            String.class, data.get("id"))).isEqualTo(SECRET);
    }

    @Test
    void register_httpUrl_returns400() {
        assertThat(post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "http://example.com/hook", "secret", SECRET)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_privateIpUrl_returns400() {
        assertThat(post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://10.0.0.5/hook", "secret", SECRET)).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_secretTooShort_returns400() {
        assertThat(post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://example.com/hook", "secret", "short")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void list_neverEchoesSecret() throws Exception {
        post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://example.com/a", "secret", SECRET));
        post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://example.com/b", "secret", SECRET));

        var resp = rest.exchange(url("/api/v1/projects/" + projectA + "/webhooks"), HttpMethod.GET,
            auth(orgA, userA, Role.ADMIN), String.class);
        var node = JSON.readValue(resp.getBody(), Map.class);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) node.get("data");

        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(i -> {
            assertThat(i.get("secretSet")).isEqualTo(Boolean.TRUE);
            assertThat(i).doesNotContainKey("secret");
        });
    }

    @Test
    void delete_removesEndpoint_204() throws Exception {
        var id = data(post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://example.com/hook", "secret", SECRET))).get("id");

        var resp = rest.exchange(url("/api/v1/webhooks/" + id), HttpMethod.DELETE,
            auth(orgA, userA, Role.ADMIN), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_endpoint WHERE id=?::uuid",
            Integer.class, id)).isZero();
    }

    @Test
    void delete_unknownId_404() {
        var resp = rest.exchange(url("/api/v1/webhooks/" + UUID.randomUUID()), HttpMethod.DELETE,
            auth(orgA, userA, Role.ADMIN), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("WEBHOOK_ENDPOINT_NOT_FOUND");
    }

    @Test
    void memberRole_forbidden_403() {
        assertThat(post(projectA, orgA, userA, Role.MEMBER,
            Map.of("url", "https://example.com/hook", "secret", SECRET)).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void orgB_cannotSeeOrDeleteOrgAEndpoint() throws Exception {
        var id = data(post(projectA, orgA, userA, Role.ADMIN,
            Map.of("url", "https://example.com/hook", "secret", SECRET))).get("id");
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);

        var listResp = rest.exchange(url("/api/v1/projects/" + projectA + "/webhooks"), HttpMethod.GET,
            auth(orgB, userB, Role.ADMIN), String.class);
        var delResp = rest.exchange(url("/api/v1/webhooks/" + id), HttpMethod.DELETE,
            auth(orgB, userB, Role.ADMIN), String.class);

        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_endpoint WHERE id=?::uuid",
            Integer.class, id)).isEqualTo(1);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Void> auth(UUID orgId, UUID userId, Role role) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userId, orgId, role));
        return new HttpEntity<>(headers);
    }

    private org.springframework.http.ResponseEntity<String> post(UUID projectId, UUID orgId, UUID userId,
                                                                 Role role, Map<String, Object> body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userId, orgId, role));
        headers.add("Content-Type", "application/json");
        return rest.exchange(url("/api/v1/projects/" + projectId + "/webhooks"), HttpMethod.POST,
            new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(org.springframework.http.ResponseEntity<String> resp)
            throws Exception {
        var node = JSON.readValue(resp.getBody(), Map.class);
        return (Map<String, Object>) node.get("data");
    }
}
