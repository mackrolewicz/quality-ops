package com.qualityops.api.scm;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-009 §11 — repository-connection CRUD: org isolation, no token ever
 *  echoed, and a soft-delete blocked while a {@code repo_test} case references
 *  the connection (409 {@code CONNECTION_IN_USE}). */
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
class RepositoryConnectionControllerIT {

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
    private UUID projectA;

    @BeforeEach
    void seed() {
        orgA = ItFixtures.insertOrg(jdbc);
        userA = ItFixtures.insertUser(jdbc, orgA);
        projectA = ItFixtures.insertProject(jdbc, orgA);
    }

    private Map<String, Object> registerBody() {
        return Map.of("provider", "GITHUB", "ownerPath", "acme", "repoName", "web",
            "defaultRef", "main", "credentialRef", "GH_PAT");
    }

    @Test
    void register_returns201_echoesCredentialRefNeverAToken() throws Exception {
        var resp = post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA, Role.ADMIN,
            registerBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var data = data(resp);
        assertThat(data.get("credentialRef")).isEqualTo("GH_PAT");
        assertThat(data.get("host")).isEqualTo("github.com");
        assertThat(data).doesNotContainKey("token");
        assertThat(data).doesNotContainKey("credential");
    }

    @Test
    void register_memberRole_forbidden() {
        assertThat(post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA, Role.MEMBER,
            registerBody()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void get_foreignOrg_returns404() throws Exception {
        var id = data(post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA, Role.ADMIN,
            registerBody())).get("id");
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);

        var resp = rest.exchange(url("/api/v1/repository-connections/" + id), HttpMethod.GET,
            auth(orgB, userB, Role.ADMIN), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).contains("REPOSITORY_CONNECTION_NOT_FOUND");
    }

    @Test
    void update_changesOwnerPathAndCredentialRef() throws Exception {
        var id = data(post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA, Role.ADMIN,
            registerBody())).get("id");

        var resp = rest.exchange(url("/api/v1/repository-connections/" + id), HttpMethod.PUT,
            jsonEntity(orgA, userA, Role.ADMIN, Map.of("ownerPath", "acme", "repoName", "api",
                "defaultRef", "release", "credentialRef", "GH_PAT2")), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var data = data(resp);
        assertThat(data.get("repoName")).isEqualTo("api");
        assertThat(data.get("defaultRef")).isEqualTo("release");
        assertThat(data.get("credentialRef")).isEqualTo("GH_PAT2");
    }

    @Test
    void delete_notReferenced_returns204() throws Exception {
        var id = data(post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA, Role.ADMIN,
            registerBody())).get("id");

        var resp = rest.exchange(url("/api/v1/repository-connections/" + id), HttpMethod.DELETE,
            auth(orgA, userA, Role.ADMIN), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NOT NULL FROM repository_connection WHERE id = ?::uuid", Boolean.class, id))
            .isTrue();
    }

    @Test
    void delete_referencedByRepoTestCase_returns409ConnectionInUse() throws Exception {
        var id = (String) data(post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA,
            Role.ADMIN, registerBody())).get("id");
        var suiteId = ItFixtures.insertSuite(jdbc, orgA, projectA);
        jdbc.update("INSERT INTO test_cases (suite_id, org_id, name, order_index, repo_test) "
                + "VALUES (?, ?, ?, ?, ?::jsonb)",
            suiteId, orgA, "repo case", 0, "{\"repositoryConnectionId\":\"" + id + "\"}");

        var resp = rest.exchange(url("/api/v1/repository-connections/" + id), HttpMethod.DELETE,
            auth(orgA, userA, Role.ADMIN), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).contains("CONNECTION_IN_USE");
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at IS NULL FROM repository_connection WHERE id = ?::uuid", Boolean.class, id))
            .isTrue();
    }

    @Test
    void list_isOrgScoped() throws Exception {
        post("/api/v1/projects/" + projectA + "/repository-connections", orgA, userA, Role.ADMIN, registerBody());
        var orgB = ItFixtures.insertOrg(jdbc);
        var userB = ItFixtures.insertUser(jdbc, orgB);

        var resp = rest.exchange(url("/api/v1/projects/" + projectA + "/repository-connections"),
            HttpMethod.GET, auth(orgB, userB, Role.ADMIN), String.class);

        // project belongs to orgA -> orgB gets 404 on the ownership check
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

    private HttpEntity<Object> jsonEntity(UUID orgId, UUID userId, Role role, Object body) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(userId, orgId, role));
        headers.add("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> post(String path, UUID orgId, UUID userId, Role role, Map<String, ?> body) {
        return rest.exchange(url(path), HttpMethod.POST, jsonEntity(orgId, userId, role, body), String.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<String> resp) throws Exception {
        return (Map<String, Object>) JSON.readValue(resp.getBody(), Map.class).get("data");
    }
}
