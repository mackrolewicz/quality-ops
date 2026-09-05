package com.qualityops.worker.execution.application.service;

import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Artifacts;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactStagingSweeperTest {

    @Test
    void sweep_deletesOldStagedFiles_keepsFreshOnes_andIgnoresUnrelatedFiles(@TempDir Path dir)
            throws IOException {
        Path oldStaged = write(dir, "staged-old.png");
        Files.setLastModifiedTime(oldStaged, FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        Path freshStaged = write(dir, "staged-fresh.zip");
        Path unrelated = write(dir, "keep-me.txt");
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minus(Duration.ofDays(10))));

        sweeper(dir).sweep();

        assertThat(Files.exists(oldStaged)).isFalse();
        assertThat(Files.exists(freshStaged)).isTrue();
        assertThat(Files.exists(unrelated)).as("only staged- files are swept").isTrue();
    }

    private ArtifactStagingSweeper sweeper(Path dir) {
        var artifacts = new Artifacts(true, "http://localhost:9000", "b", "k", "s", "us-east-1",
            Artifacts.Sse.S3, true, Duration.ofSeconds(10), 10_485_760L, 30,
            dir.toString(), Duration.ofHours(2), false, false);
        WorkerExecutionProperties props = TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5),
            artifacts, TestProps.retry(), TestProps.secrets());
        return new ArtifactStagingSweeper(props);
    }

    private static Path write(Path dir, String name) throws IOException {
        return Files.write(dir.resolve(name), new byte[16]);
    }
}
