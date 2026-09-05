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

/** Mirrors {@link AttemptRetentionSweeper}: deletes browser artifact files older
 *  than the configured retention. Temp storage only — no durable store in 2B2. */
@Component
public class BrowserArtifactSweeper {

    private static final Logger log = LoggerFactory.getLogger(BrowserArtifactSweeper.class);

    private final WorkerExecutionProperties props;

    public BrowserArtifactSweeper(WorkerExecutionProperties props) {
        this.props = props;
    }

    @Scheduled(fixedDelayString = "PT30M")
    void sweep() {
        Path dir = props.browser().artifactTempDirPath();
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(props.browser().artifactRetention());
        int deleted = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (isArtifact(p) && isOlderThan(p, cutoff) && deleteQuietly(p)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("Browser artifact sweep failed to list {}", dir, e);
        }
        if (deleted > 0) {
            log.info("Swept {} browser artifact files older than {}", deleted, props.browser().artifactRetention());
        }
    }

    /** Only the files this driver writes — {@code <id>-<id>-<nanos>.png} and
     *  {@code …-trace.zip} — so a misconfigured temp dir cannot lose unrelated files. */
    private static boolean isArtifact(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".png") || name.endsWith("-trace.zip");
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
