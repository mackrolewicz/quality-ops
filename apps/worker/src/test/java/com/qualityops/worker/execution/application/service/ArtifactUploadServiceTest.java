package com.qualityops.worker.execution.application.service;

import com.qualityops.events.ArtifactReference;
import com.qualityops.events.ArtifactType;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.application.port.out.ArtifactStoragePort;
import com.qualityops.worker.execution.domain.ArtifactUpload;
import com.qualityops.worker.execution.domain.BrowserRunMetadata;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.execution.domain.StoredArtifact;
import com.qualityops.worker.execution.exception.ArtifactStoreException;
import com.qualityops.worker.support.EmptyObjectProvider;
import com.qualityops.worker.support.TestProps;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactUploadServiceTest {

    private final UUID orgId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @Test
    void artifactsDisabled_reportsStoreDisabled_neverThrows(@TempDir Path dir) throws IOException {
        var svc = new ArtifactUploadService(propsWith(TestProps.artifacts()), EmptyObjectProvider.instance(), EmptyObjectProvider.instance());

        var refs = svc.uploadForCase(event(), resultWithScreenshot(screenshot(dir, 10), 0), false);

        assertThat(refs).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ArtifactReference.Availability.UNAVAILABLE);
            assertThat(r.unavailableReason()).isEqualTo("store-disabled");
        });
    }

    @Test
    void oversizeFile_reportsTooLarge(@TempDir Path dir) throws IOException {
        var artifacts = TestProps.artifacts(true, "http://localhost:9000", "k", "s", dir, false);
        var tiny = new ArtifactUploadService(shrinkMax(propsWith(artifacts), 4), provider(new RecordingStore()),
            EmptyObjectProvider.instance());

        var refs = tiny.uploadForCase(event(), resultWithScreenshot(screenshot(dir, 4096), 0), false);

        assertThat(refs).singleElement().satisfies(r ->
            assertThat(r.unavailableReason()).isEqualTo("too-large"));
    }

    @Test
    void secretCaseWithUploadGateOff_reportsSuppressedSecretCase(@TempDir Path dir) throws IOException {
        var artifacts = TestProps.artifacts(true, "http://localhost:9000", "k", "s", dir, false);
        var svc = new ArtifactUploadService(propsWith(artifacts), provider(new RecordingStore()),
            EmptyObjectProvider.instance());

        var refs = svc.uploadForCase(event(), resultWithScreenshot(screenshot(dir, 10), 0), true);

        assertThat(refs).singleElement().satisfies(r ->
            assertThat(r.unavailableReason()).isEqualTo("suppressed-secret-case"));
    }

    @Test
    void successfulUpload_reportsAvailableWithOrgFirstKey_andDeletesStagedFile(@TempDir Path dir)
            throws IOException {
        var stagingDir = dir.resolve("staging");
        var artifacts = TestProps.artifacts(true, "http://localhost:9000", "k", "s", stagingDir, false);
        var store = new RecordingStore();
        var svc = new ArtifactUploadService(propsWith(artifacts), provider(store), EmptyObjectProvider.instance());

        var refs = svc.uploadForCase(event(), resultWithScreenshot(screenshot(dir, 128), 0), false);

        assertThat(refs).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ArtifactReference.Availability.AVAILABLE);
            assertThat(r.artifactType()).isEqualTo(ArtifactType.SCREENSHOT);
            assertThat(r.storageKey()).startsWith("org/" + orgId + "/run/" + runId);
            assertThat(r.storageKey()).contains("/attempt/0/SCREENSHOT/");
        });
        assertThat(store.lastUpload.ref().storageKey()).startsWith("org/" + orgId + "/");
        try (var s = Files.list(stagingDir)) {
            assertThat(s).as("staged file deleted after success").isEmpty();
        }
    }

    @Test
    void storeUnreachable_degradesToUnavailable_neverThrows_andKeepsStagedFile(@TempDir Path dir)
            throws IOException {
        var stagingDir = dir.resolve("staging");
        var artifacts = TestProps.artifacts(true, "http://localhost:9000", "k", "s", stagingDir, false);
        var svc = new ArtifactUploadService(propsWith(artifacts), provider(new ThrowingStore()),
            EmptyObjectProvider.instance());

        var refs = svc.uploadForCase(event(), resultWithScreenshot(screenshot(dir, 128), 0), false);

        assertThat(refs).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ArtifactReference.Availability.UNAVAILABLE);
            assertThat(r.unavailableReason()).isEqualTo("store-unreachable");
        });
        try (var s = Files.list(stagingDir)) {
            assertThat(s).as("staged file kept for the sweeper on failure").isNotEmpty();
        }
    }

    // ---- helpers ----

    private WorkerExecutionProperties propsWith(WorkerExecutionProperties.Artifacts artifacts) {
        return TestProps.defaults(Mode.AUTO, Duration.ofMinutes(5), artifacts,
            TestProps.retry(), TestProps.secrets());
    }

    private WorkerExecutionProperties shrinkMax(WorkerExecutionProperties p, long maxBytes) {
        var a = p.artifacts();
        var shrunk = new WorkerExecutionProperties.Artifacts(a.enabled(), a.endpoint(), a.bucket(),
            a.accessKey(), a.secretKey(), a.region(), a.sse(), a.pathStyleAccess(), a.uploadTimeout(),
            maxBytes, a.retentionDays(), a.stagingDir(), a.stagingRetention(), a.bootstrapEnabled(),
            a.uploadSecretCases());
        return propsWith(shrunk);
    }

    private static ObjectProvider<ArtifactStoragePort> provider(ArtifactStoragePort port) {
        return EmptyObjectProvider.of(port);
    }

    private Path screenshot(Path dir, int bytes) throws IOException {
        Path p = dir.resolve("shot-" + UUID.randomUUID() + ".png");
        Files.write(p, new byte[bytes]);
        return p;
    }

    private CaseExecutionResult resultWithScreenshot(Path shot, int attemptEpoch) throws IOException {
        var meta = new BrowserRunMetadata(List.of(), List.of(), "http://x/", 0, 0,
            shot.toString(), Files.size(shot), null, 0L);
        return new CaseExecutionResult(caseId, "case", 0, CaseStatus.FAILED, Duration.ofMillis(1),
            null, null, List.of(), "boom", meta, SideEffectClass.NONE_OBSERVED, attemptEpoch);
    }

    private RunRequestedEvent event() {
        return new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            java.time.Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(caseId, "case", 0)));
    }

    private static final class RecordingStore implements ArtifactStoragePort {
        private ArtifactUpload lastUpload;

        @Override
        public StoredArtifact put(ArtifactUpload upload) {
            this.lastUpload = upload;
            return new StoredArtifact(upload.ref().storageKey(), upload.contentType(),
                upload.sizeBytes(), upload.sha256(), false);
        }
    }

    private static final class ThrowingStore implements ArtifactStoragePort {
        @Override
        public StoredArtifact put(ArtifactUpload upload) throws ArtifactStoreException {
            throw new ArtifactStoreException("minio down");
        }
    }
}
