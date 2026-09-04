package com.qualityops.worker.execution.adapter.out.secret;

import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Secrets;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.exception.SecretNotFoundException;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvFileSecretResolverTest {

    private WorkerExecutionProperties props(Secrets secrets) {
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            TestProps.artifacts(), TestProps.retry(), secrets);
    }

    @Test
    void resolve_keyPresentInEnvironment_returnsPlaintext() {
        var resolver = new EnvFileSecretResolver(
            props(new Secrets("QUALITYOPS_SECRET_", null)),
            Map.of("QUALITYOPS_SECRET_DEMO_PASSWORD", "hunter2")::get);

        assertThat(resolver.resolve("DEMO_PASSWORD")).isEqualTo("hunter2");
    }

    @Test
    void resolve_keyOnlyInFile_returnsPlaintextFromFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "API_TOKEN=s3cr3t-from-file\n");
        var resolver = new EnvFileSecretResolver(
            props(new Secrets("QUALITYOPS_SECRET_", file.toString())),
            key -> null);

        assertThat(resolver.resolve("API_TOKEN")).isEqualTo("s3cr3t-from-file");
    }

    @Test
    void resolve_environmentWins_overFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "API_TOKEN=from-file\n");
        var resolver = new EnvFileSecretResolver(
            props(new Secrets("QUALITYOPS_SECRET_", file.toString())),
            Map.of("QUALITYOPS_SECRET_API_TOKEN", "from-env")::get);

        assertThat(resolver.resolve("API_TOKEN")).isEqualTo("from-env");
    }

    @Test
    void resolve_unknownKey_throwsSecretNotFoundException() {
        var resolver = new EnvFileSecretResolver(
            props(new Secrets("QUALITYOPS_SECRET_", null)), key -> null);

        assertThatThrownBy(() -> resolver.resolve("MISSING"))
            .isInstanceOf(SecretNotFoundException.class)
            .hasMessageContaining("MISSING");
    }
}
