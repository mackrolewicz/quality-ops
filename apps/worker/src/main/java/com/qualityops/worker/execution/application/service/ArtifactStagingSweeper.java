package com.qualityops.worker.execution.application.service;

import com.qualityops.worker.config.WorkerExecutionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.stream.Stream;

/**
 * Sweeps the artifact <em>staging</em> dir ({@code artifacts.staging-dir}):
 * files that were captured, staged for upload, and either uploaded (safe to
 * delete) or failed to upload (kept up to {@code artifacts.staging-retention}
 * for the operator, then dropped). ADR-004's {@code BrowserArtifactSweeper}
 * still owns the browser <em>capture</em> dir; durable retention is the object
 * store's lifecycle rule.
 */
@Component
public class ArtifactStagingSweeper {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStagingSweeper.class);
    static final String STAGED_PREFIX = "staged-";

    private final WorkerExecutionProperties props;

    public ArtifactStagingSweeper(WorkerExecutionProperties props) {
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT30M")
    void sweep() {
        var artifacts = props.artifacts();
        if (artifacts == null) {
            return;
        }
        Path dir = artifacts.stagingDirPath();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(artifacts.stagingRetention());
        int deleted = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (isStaged(p) && isOlderThan(p, cutoff) && deleteQuietly(p)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("Artifact staging sweep failed to list {}", dir, e);
        }
        if (deleted > 0) {
            log.info("Swept {} staged artifact files older than {}", deleted, artifacts.stagingRetention());
        }
    }

    /** Only files this service stages — so a misconfigured dir cannot lose unrelated files. */
    private static boolean isStaged(Path p) {
        return p.getFileName().toString().startsWith(STAGED_PREFIX);
    }

    private static boolean isOlderThan(Path p, Instant cutoff) {
        try {
            return Files.isRegularFile(p) && Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }

    private boolean deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
            return true;
        } catch (IOException e) {
            log.debug("Could not delete {}", p, e);
            return false;
        }
    }
}
