package com.qualityops.api.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-007 §6.1 — the hand-maintained Caseflow OpenAPI 3.1 doc cannot silently
 * rot: it must parse and declare the five operationIds + the RunCompletedWebhook
 * schema + the four signature headers. Structural (SnakeYAML) — swagger-parser is
 * not assumed on the classpath.
 */
class CaseflowContractTest {

    private static final List<String> EXPECTED_OPERATION_IDS = List.of(
        "submitCaseflowRun", "getRun", "cancelRun", "listRunResults", "listRunArtifacts");

    private static final List<String> SIGNATURE_HEADERS = List.of(
        "X-QualityOps-Event", "X-QualityOps-Delivery", "X-QualityOps-Timestamp", "X-QualityOps-Signature");

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSpec() throws IOException {
        Path spec = locate();
        try (InputStream in = Files.newInputStream(spec)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }

    private static Path locate() {
        Path cursor = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && cursor != null; i++) {
            Path candidate = cursor.resolve("docs/api/caseflow-v1.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("docs/api/caseflow-v1.yaml not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void spec_parsesAsOpenApi31() throws IOException {
        Map<String, Object> spec = loadSpec();

        assertThat(spec.get("openapi")).asString().startsWith("3.1");
        assertThat(((Map<?, ?>) spec.get("info")).get("version")).isEqualTo("1.0.0");
    }

    @Test
    @SuppressWarnings("unchecked")
    void spec_declaresTheFiveOperationIds() throws IOException {
        Map<String, Object> paths = (Map<String, Object>) loadSpec().get("paths");

        List<String> operationIds = new ArrayList<>();
        for (Object pathItem : paths.values()) {
            for (Object op : ((Map<String, Object>) pathItem).values()) {
                Object id = ((Map<String, Object>) op).get("operationId");
                if (id != null) {
                    operationIds.add(id.toString());
                }
            }
        }
        assertThat(operationIds).containsAll(EXPECTED_OPERATION_IDS);
    }

    @Test
    @SuppressWarnings("unchecked")
    void spec_declaresRunCompletedWebhookSchema() throws IOException {
        Map<String, Object> components = (Map<String, Object>) loadSpec().get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertThat(schemas).containsKey("RunCompletedWebhook");
        assertThat((Map<String, Object>) components.get("securitySchemes")).containsKey("bearerAuth");
    }

    @Test
    @SuppressWarnings("unchecked")
    void spec_webhookDeclaresTheFourSignatureHeaders() throws IOException {
        Map<String, Object> webhooks = (Map<String, Object>) loadSpec().get("webhooks");
        Map<String, Object> runCompleted = (Map<String, Object>) webhooks.get("runCompleted");
        Map<String, Object> post = (Map<String, Object>) runCompleted.get("post");
        List<Map<String, Object>> params = (List<Map<String, Object>>) post.get("parameters");

        List<String> headerNames = params.stream()
            .filter(p -> "header".equals(p.get("in")))
            .map(p -> p.get("name").toString())
            .toList();
        assertThat(headerNames).containsAll(SIGNATURE_HEADERS);
    }
}
