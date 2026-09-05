package com.qualityops.worker.execution.application.service;

import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;

class BrowserArtifactSweeperTest {

    @Test
    void sweep_filesOlderThanRetention_deleted_newerKept(@TempDir Path dir) throws Exception {
        Path old = Files.write(dir.resolve("old.png"), new byte[] {1});
        Path fresh = Files.write(dir.resolve("fresh.png"), new byte[] {2});
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        var props = TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), null, null, false,
            TestProps.browser(dir, Duration.ofHours(1)));
        new BrowserArtifactSweeper(props).sweep();

        assertThat(Files.exists(old)).isFalse();
        assertThat(Files.exists(fresh)).isTrue();
    }

    @Test
    void sweep_oldNonArtifactFile_kept(@TempDir Path dir) throws Exception {
        Path unrelated = Files.write(dir.resolve("notes.txt"), new byte[] {9});
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        var props = TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), null, null, false,
            TestProps.browser(dir, Duration.ofHours(1)));
        new BrowserArtifactSweeper(props).sweep();

        assertThat(Files.exists(unrelated)).isTrue();
    }

    @Test
    void sweep_dirMissing_noThrow(@TempDir Path dir) {
        var missing = dir.resolve("does-not-exist");
        var props = TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), null, null, false,
            TestProps.browser(missing, Duration.ofHours(1)));

        new BrowserArtifactSweeper(props).sweep();
    }
}
