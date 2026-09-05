package com.qualityops.worker.execution.application.service;

import com.qualityops.events.ArtifactReference;
import com.qualityops.events.ArtifactType;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.execution.application.port.out.ArtifactStoragePort;
import com.qualityops.worker.execution.domain.ArtifactRef;
import com.qualityops.worker.execution.domain.ArtifactUpload;
import com.qualityops.worker.execution.domain.BrowserRunMetadata;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.RepoExecutionMetadata;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Best-effort, per-case artifact upload. Runs on the per-case execution path
 * <em>after</em> a case's in-run retries. It NEVER throws and NEVER blocks or
 * fails the terminal event: a slow or failing store simply yields an
 * {@code UNAVAILABLE} {@link ArtifactReference} and the staged file is left for
 * {@link ArtifactStagingSweeper}.
 */
@Component
public class ArtifactUploadService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactUploadService.class);

    private final WorkerExecutionProperties props;
    private final ObjectProvider<ArtifactStoragePort> storageProvider;
    private final ObjectProvider<com.qualityops.worker.config.RepoExecWorkerProperties> repoExecPropsProvider;
    private final ExecutorService uploadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public ArtifactUploadService(WorkerExecutionProperties props,
                                 ObjectProvider<ArtifactStoragePort> storageProvider,
                                 ObjectProvider<com.qualityops.worker.config.RepoExecWorkerProperties>
                                     repoExecPropsProvider) {
        this.props = props;
        this.storageProvider = storageProvider;
        this.repoExecPropsProvider = repoExecPropsProvider;
    }

    @PreDestroy
    void shutdown() {
        uploadExecutor.shutdownNow();
    }

    /**
     * @param secretCase whether this case used any {@code secretRef} (gates artifact upload).
     * @return one reference per capture file the case produced; never null, may be empty.
     */
    public List<ArtifactReference> uploadForCase(RunRequestedEvent event, CaseExecutionResult result,
                                                 boolean secretCase) {
        var files = captureFiles(result);
        if (files.isEmpty()) {
            return List.of();
        }
        var a = props.artifacts();
        ArtifactStoragePort storage = a != null && a.enabled() ? storageProvider.getIfAvailable() : null;
        var refs = new ArrayList<ArtifactReference>(files.size());
        for (CaptureFile f : files) {
            refs.add(handle(event, result.testCaseId(), result.attemptEpoch(), f, secretCase, a, storage));
        }
        return List.copyOf(refs);
    }

    private ArtifactReference handle(RunRequestedEvent event, UUID caseId, int attemptEpoch, CaptureFile f,
                                     boolean secretCase, WorkerExecutionProperties.Artifacts a,
                                     ArtifactStoragePort storage) {
        if (a == null || !a.enabled() || storage == null) {
            return unavailable(f.type(), "store-disabled");
        }
        if (secretCase) {
            boolean repoArtifact = f.type() == ArtifactType.CONSOLE_LOG || f.type() == ArtifactType.REPORT;
            if (repoArtifact) {
                var repoProps = repoExecPropsProvider.getIfAvailable();
                if (repoProps == null || !repoProps.uploadSecretRunArtifacts()) {
                    log.debug("Suppressing artifact {} for secret-bearing repository run "
                        + "(upload-secret-run-artifacts=false)", f.type());
                    return unavailable(f.type(), "suppressed-secret-run");
                }
            } else if (!a.uploadSecretCases()) {
                log.debug("Suppressing artifact {} for secret-bearing case (upload-secret-cases=false)", f.type());
                return unavailable(f.type(), "suppressed-secret-case");
            }
        }
        if (f.bytes() > a.maxArtifactBytes()) {
            return unavailable(f.type(), "too-large");
        }

        Path staged = null;
        try {
            staged = stage(a.stagingDirPath(), f);
            long size = Files.size(staged);
            String sha = sha256(staged);
            // Deterministic object name (ADR-005 §1.2): the storage key must be a pure
            // function of ids + epoch + type so a redelivered / lease-stolen re-run
            // re-PUTs the SAME key (statObject dedup hits, no orphaned objects). The
            // random UUID is only the on-disk staging filename, never the object name.
            var ref = new ArtifactRef(event.orgId(), event.runId(), event.executionId(),
                caseId, attemptEpoch, f.type(), objectName(f));
            var upload = new ArtifactUpload(ref, staged, contentType(f), size, sha);

            var stored = putWithTimeout(storage, upload, a.uploadTimeout());
            Files.deleteIfExists(staged);
            return new ArtifactReference(f.type(), stored.storageKey(), stored.contentType(),
                stored.sizeBytes(), ArtifactReference.Availability.AVAILABLE, null);
        } catch (TimeoutException e) {
            log.warn("Artifact upload for run {} timed out after {} — recording UNAVAILABLE",
                event.runId(), a.uploadTimeout());
            return unavailable(f.type(), "timeout");
        } catch (RuntimeException | IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Artifact upload for run {} failed ({}) — recording UNAVAILABLE, staged file kept",
                event.runId(), e.getClass().getSimpleName());
            return unavailable(f.type(), "store-unreachable");
        }
    }

    private com.qualityops.worker.execution.domain.StoredArtifact putWithTimeout(
            ArtifactStoragePort storage, ArtifactUpload upload, Duration timeout)
            throws TimeoutException, InterruptedException {
        Future<com.qualityops.worker.execution.domain.StoredArtifact> future =
            uploadExecutor.submit(() -> storage.put(upload));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            future.cancel(true);
            throw new IllegalStateException("artifact PUT failed", e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    private static Path stage(Path stagingDir, CaptureFile f) throws IOException {
        Files.createDirectories(stagingDir);
        String ext = extensionFor(f);
        Path dest = stagingDir.resolve(ArtifactStagingSweeper.STAGED_PREFIX + UUID.randomUUID() + ext);
        Files.copy(Path.of(f.path()), dest);
        return dest;
    }

    private static String extensionFor(CaptureFile f) {
        if (f.objectNameOverride() != null) {
            int dot = f.objectNameOverride().lastIndexOf('.');
            if (dot >= 0) {
                return f.objectNameOverride().substring(dot);
            }
        }
        return f.type() == ArtifactType.SCREENSHOT ? ".png" : ".zip";
    }

    /** Stable object filename — the last segment of the deterministic storage
     *  key. A repository capture (report file / console log) carries its own
     *  name; browser captures use one fixed name per type. */
    private static String objectName(CaptureFile f) {
        if (f.objectNameOverride() != null) {
            return f.objectNameOverride();
        }
        return f.type() == ArtifactType.SCREENSHOT ? "screenshot.png" : "trace.zip";
    }

    private static String sha256(Path file) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("cannot hash staged artifact", e);
        }
    }

    private static String contentType(CaptureFile f) {
        return switch (f.type()) {
            case SCREENSHOT -> "image/png";
            case TRACE -> "application/zip";
            case CONSOLE_LOG -> "text/plain";
            case REPORT -> "application/octet-stream";
            default -> "application/octet-stream";
        };
    }

    private static List<CaptureFile> captureFiles(CaseExecutionResult result) {
        var files = new ArrayList<CaptureFile>(2);
        BrowserRunMetadata b = result.browser();
        if (b != null) {
            if (b.screenshotTempPath() != null) {
                files.add(new CaptureFile(ArtifactType.SCREENSHOT, b.screenshotTempPath(), b.screenshotBytes(),
                    null));
            }
            if (b.traceTempPath() != null) {
                files.add(new CaptureFile(ArtifactType.TRACE, b.traceTempPath(), b.traceBytes(), null));
            }
        }
        RepoExecutionMetadata repo = result.repository();
        if (repo != null) {
            if (repo.consoleLogTempPath() != null) {
                files.add(new CaptureFile(ArtifactType.CONSOLE_LOG, repo.consoleLogTempPath(),
                    repo.consoleLogBytes(), "console.log"));
            }
            for (var capture : repo.reportCaptures()) {
                files.add(new CaptureFile(ArtifactType.REPORT, capture.tempPath(), capture.bytes(),
                    capture.objectName()));
            }
        }
        return files;
    }

    private static ArtifactReference unavailable(ArtifactType type, String reason) {
        return new ArtifactReference(type, null, null, null,
            ArtifactReference.Availability.UNAVAILABLE, reason);
    }

    /** @param objectNameOverride nullable — when present, used verbatim as the
     *                            storage key's filename segment (a repository
     *                            capture); otherwise a fixed per-type name. */
    private record CaptureFile(ArtifactType type, String path, long bytes, String objectNameOverride) {}
}
