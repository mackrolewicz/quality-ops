package com.qualityops.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.qualityops.events.ArtifactReference;
import com.qualityops.events.CaseResultSummary;
import com.qualityops.events.ResultChunkEvent;
import com.qualityops.events.RunCompletedEvent;
import com.qualityops.events.RunRequestedEvent;
import com.qualityops.events.TestCaseSnapshotItem;
import com.qualityops.worker.config.WorkerExecutionProperties;
import com.qualityops.worker.config.WorkerExecutionProperties.Artifacts;
import com.qualityops.worker.config.WorkerExecutionProperties.Mode;
import com.qualityops.worker.execution.application.port.out.ArtifactStoragePort;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore;
import com.qualityops.worker.execution.application.port.out.ExecutionAttemptStore.Claimed;
import com.qualityops.worker.execution.application.port.out.ExecutionRunner;
import com.qualityops.worker.execution.application.port.out.RunLifecyclePublisher;
import com.qualityops.worker.execution.application.port.out.RunnerKind;
import com.qualityops.worker.execution.application.service.ArtifactUploadService;
import com.qualityops.worker.execution.application.service.ExecutionRunnerResolver;
import com.qualityops.worker.execution.application.service.RunExecutionService;
import com.qualityops.worker.execution.application.service.Sleeper;
import com.qualityops.worker.execution.adapter.out.storage.S3ArtifactStorage;
import com.qualityops.worker.execution.domain.BrowserRunMetadata;
import com.qualityops.worker.execution.domain.CaseExecutionContext;
import com.qualityops.worker.execution.domain.CaseExecutionResult;
import com.qualityops.worker.execution.domain.CaseStatus;
import com.qualityops.worker.execution.domain.SideEffectClass;
import com.qualityops.worker.execution.exception.ArtifactStoreException;
import com.qualityops.worker.support.EmptyObjectProvider;
import com.qualityops.worker.support.TestProps;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.testcontainers.containers.MinIOContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ADR-005 §1.4 hard invariant: a failed/slow artifact upload can NEVER delay or
 *  fail {@code runs.completed}; a successful one lands at the org-first key. */
class RunExecutionServiceArtifactIT {

    private static final String BUCKET = "qualityops-artifacts";
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z");
    private static MinioClient client;

    @BeforeAll
    static void start() throws Exception {
        MINIO.start();
        client = MinioClient.builder().endpoint(MINIO.getS3URL())
            .credentials(MINIO.getUserName(), MINIO.getPassword()).build();
        client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void stop() {
        MINIO.stop();
    }

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Sleeper noopSleeper = ms -> { };
    private final UUID runId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @Test
    void browserFailScreenshot_landsInMinioUnderOrgFirstKey_andChunkAndTerminalCarryAvailableRef(
            @TempDir Path dir) throws Exception {
        var storage = new S3ArtifactStorage(client, minioProps(dir));
        var harness = harness(new UploadServiceWith(storage, minioProps(dir)));
        harness.service.processRunRequested(event());

        var chunk = ArgumentCaptor.forClass(ResultChunkEvent.class);
        verify(harness.publisher).publishResultChunk(chunk.capture());
        var ref = chunk.getValue().artifacts().get(0);
        assertThat(ref.status()).isEqualTo(ArtifactReference.Availability.AVAILABLE);
        assertThat(ref.storageKey()).startsWith("org/" + orgId + "/run/" + runId);

        // object really exists in MinIO
        client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(ref.storageKey()).build());

        var completed = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(harness.publisher).publishRunCompleted(completed.capture());
        var summaryRef = completed.getValue().caseResults().get(0).artifacts().get(0);
        assertThat(summaryRef.storageKey()).isEqualTo(ref.storageKey());
        assertThat(completed.getValue().caseResults().get(0).verdict())
            .isEqualTo(CaseResultSummary.Verdict.FAILED);
    }

    @Test
    void storeUnreachableMidRun_everyRefUnavailable_butRunStillCompletesWithCorrectVerdict(
            @TempDir Path dir) throws Exception {
        ArtifactStoragePort down = mock(ArtifactStoragePort.class);
        when(down.put(any())).thenThrow(new ArtifactStoreException("minio unreachable"));
        var harness = harness(new UploadServiceWith(down, minioProps(dir)));

        harness.service.processRunRequested(event());

        var completed = ArgumentCaptor.forClass(RunCompletedEvent.class);
        verify(harness.publisher).publishRunCompleted(completed.capture());
        var summary = completed.getValue().caseResults().get(0);
        assertThat(summary.verdict()).isEqualTo(CaseResultSummary.Verdict.FAILED);
        assertThat(summary.artifacts()).allSatisfy(r ->
            assertThat(r.status()).isEqualTo(ArtifactReference.Availability.UNAVAILABLE));
    }

    // ---- harness ----

    private record Harness(RunExecutionService service, RunLifecyclePublisher publisher) {}

    private Harness harness(ArtifactUploadService uploads) throws IOException {
        var publisher = mock(RunLifecyclePublisher.class);
        var store = mock(ExecutionAttemptStore.class);
        when(store.claim(any(), any(), any(), any())).thenReturn(new Claimed(0));
        when(store.markCompleted(any(), anyInt(), any(), any())).thenReturn(true);
        var resolver = mock(ExecutionRunnerResolver.class);
        when(resolver.resolvedKindFor(any())).thenReturn(RunnerKind.BROWSER);
        var runner = mock(ExecutionRunner.class);
        when(resolver.resolve(any())).thenReturn(runner);
        Path shot = Files.write(Files.createTempFile("shot", ".png"), new byte[256]);
        when(runner.execute(any())).thenAnswer(inv -> {
            var ctx = (CaseExecutionContext) inv.getArgument(0);
            var meta = new BrowserRunMetadata(List.of(), List.of(), "http://x/", 1, 1,
                shot.toString(), Files.size(shot), null, 0L);
            return new CaseExecutionResult(ctx.testCase().testCaseId(), "case", 0, CaseStatus.FAILED,
                Duration.ofMillis(1), null, null, List.of(), "assertion failed", meta,
                SideEffectClass.NONE_OBSERVED, ctx.attemptEpoch());
        });
        var props = minioProps(Path.of(System.getProperty("java.io.tmpdir")));
        return new Harness(new RunExecutionService(publisher, store, resolver, props, mapper,
            uploads, noopSleeper,
            new com.qualityops.worker.execution.application.CancellationRegistry(
                new com.qualityops.worker.config.CancellationProperties(null), props),
            EmptyObjectProvider.instance()), publisher);
    }

    /** ArtifactUploadService bound to a specific storage port. */
    private static final class UploadServiceWith extends ArtifactUploadService {
        UploadServiceWith(ArtifactStoragePort port, WorkerExecutionProperties props) {
            super(props, EmptyObjectProvider.of(port), EmptyObjectProvider.instance());
        }
    }

    private WorkerExecutionProperties minioProps(Path staging) {
        var artifacts = new Artifacts(true, MINIO.getS3URL(), BUCKET,
            MINIO.getUserName(), MINIO.getPassword(), "us-east-1", Artifacts.Sse.NONE, true,
            Duration.ofSeconds(10), 10_485_760L, 30, staging.resolve("staging").toString(),
            Duration.ofHours(2), false, false);
        return TestProps.defaults(Mode.REAL, Duration.ofMinutes(5), artifacts,
            TestProps.retry(), TestProps.secrets());
    }

    private RunRequestedEvent event() {
        return new RunRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), orgId, runId, executionId,
            Instant.now(), RunRequestedEvent.SCHEMA_VERSION,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            List.of(new TestCaseSnapshotItem(caseId, "case", 0)));
    }
}
