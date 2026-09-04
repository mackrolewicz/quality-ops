package com.qualityops.api.result;

import com.qualityops.api.config.JwtService;
import com.qualityops.api.execution.application.port.out.RunRepository;
import com.qualityops.api.execution.domain.RunConfigSnapshot;
import com.qualityops.api.execution.domain.RunStatus;
import com.qualityops.api.execution.domain.TestRun;
import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.domain.ArtifactAvailability;
import com.qualityops.api.result.domain.ArtifactType;
import com.qualityops.api.result.domain.TestResultArtifact;
import com.qualityops.api.support.ItFixtures;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.listener.auto-startup=false",
    "qualityops.artifacts.enabled=true",
    "qualityops.artifacts.bucket=qualityops-artifacts",
    "qualityops.cache.enabled=false",
    "qualityops.ws.enabled=false",
    "qualityops.ratelimit.enabled=false"
})
@Testcontainers
class ArtifactControllerIT {

    private static final String BUCKET = "qualityops-artifacts";

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));
    private static final MinIOContainer MINIO =
        new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z");

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("qualityops.artifacts.endpoint", MINIO::getS3URL);
        registry.add("qualityops.artifacts.access-key", MINIO::getUserName);
        registry.add("qualityops.artifacts.secret-key", MINIO::getPassword);
    }

    private static MinioClient minio;

    @BeforeAll
    static void bucket() throws Exception {
        minio = MinioClient.builder().endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        minio.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwt;
    @Autowired private RunRepository runRepository;
    @Autowired private ArtifactMetadataRepository artifacts;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgA;
    private UUID orgB;
    private UUID runId;
    private UUID caseId;
    private UUID availableId;
    private UUID unavailableId;

    @BeforeEach
    void seed() throws Exception {
        orgA = ItFixtures.insertOrg(jdbc);
        orgB = ItFixtures.insertOrg(jdbc);
        var projectId = ItFixtures.insertProject(jdbc, orgA);
        var suiteId = ItFixtures.insertSuite(jdbc, orgA, projectId);
        var envId = ItFixtures.insertEnvironment(jdbc, orgA, projectId);
        var userId = ItFixtures.insertUser(jdbc, orgA);
        caseId = ItFixtures.insertCases(jdbc, orgA, suiteId, 1).get(0);
        runId = runRepository.save(new TestRun(UUID.randomUUID(), orgA, projectId, suiteId, envId,
            UUID.randomUUID(), RunStatus.RUNNING, userId,
            new RunConfigSnapshot(List.of(
                new com.qualityops.api.execution.domain.TestCaseSnapshotItem(caseId, "Case 0", 0))),
            null, null, Instant.now())).id();

        String key = "org/" + orgA + "/run/" + runId + "/case/" + caseId + "/attempt/0/SCREENSHOT/s.png";
        byte[] png = "fake-png-bytes".getBytes();
        minio.putObject(PutObjectArgs.builder().bucket(BUCKET).object(key)
            .stream(new ByteArrayInputStream(png), png.length, -1).contentType("image/png").build());

        artifacts.upsertForCase(orgA, runId, caseId, 0, List.of(
            new TestResultArtifact(null, orgA, runId, caseId, 0, ArtifactType.SCREENSHOT, key,
                "image/png", (long) png.length, ArtifactAvailability.AVAILABLE, null, null),
            new TestResultArtifact(null, orgA, runId, caseId, 0, ArtifactType.TRACE, null, null, null,
                ArtifactAvailability.UNAVAILABLE, "store-unreachable", null)));

        availableId = jdbc.queryForObject(
            "SELECT id FROM test_result_artifacts WHERE run_id=? AND artifact_type='SCREENSHOT'",
            UUID.class, runId);
        unavailableId = jdbc.queryForObject(
            "SELECT id FROM test_result_artifacts WHERE run_id=? AND artifact_type='TRACE'",
            UUID.class, runId);
    }

    @Test
    void listForRun_returnsPresignedUrlsThatActuallyResolve() {
        var body = rest.exchange(url("/api/v1/runs/" + runId + "/artifacts"), HttpMethod.GET,
            auth(orgA), Map.class).getBody();

        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) body.get("data");
        assertThat(items).hasSize(2);
        var available = items.stream()
            .filter(i -> "AVAILABLE".equals(i.get("status"))).findFirst().orElseThrow();
        String presigned = (String) available.get("url");
        assertThat(presigned).isNotNull();
        // Pass a parsed URI so RestTemplate does not re-encode the already-encoded query string.
        var fetched = rest.getForEntity(java.net.URI.create(presigned), String.class);
        assertThat(fetched.getStatusCode())
            .as("presigned URL %s -> body %s", presigned, fetched.getBody())
            .isEqualTo(HttpStatus.OK);

        var unavailable = items.stream()
            .filter(i -> "UNAVAILABLE".equals(i.get("status"))).findFirst().orElseThrow();
        assertThat(unavailable.get("url")).isNull();
    }

    @Test
    void getArtifact_fromAnotherOrg_is404() {
        var response = rest.exchange(url("/api/v1/artifacts/" + availableId), HttpMethod.GET,
            auth(orgB), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("ARTIFACT_NOT_FOUND");
    }

    @Test
    void getArtifact_redirectTrue_returns302ToPresignedUrl() {
        var template = new org.springframework.web.client.RestTemplate(new NoRedirectFactory());

        var response = template.exchange(url("/api/v1/artifacts/" + availableId + "?redirect=true"),
            HttpMethod.GET, auth(orgA), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
    }

    @Test
    void getArtifact_unavailable_returnsNullUrl() {
        var body = rest.exchange(url("/api/v1/artifacts/" + unavailableId), HttpMethod.GET,
            auth(orgA), Map.class).getBody();

        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) body.get("data");
        assertThat(data.get("status")).isEqualTo("UNAVAILABLE");
        assertThat(data.get("url")).isNull();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpEntity<Void> auth(UUID orgId) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(jwt.generateAccessToken(UUID.randomUUID(), orgId, Role.ADMIN));
        return new HttpEntity<>(headers);
    }

    /** A request factory that does not auto-follow 3xx, so the 302 is observable. */
    private static final class NoRedirectFactory
            extends org.springframework.http.client.SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                throws java.io.IOException {
            super.prepareConnection(connection, httpMethod);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
