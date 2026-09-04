package com.qualityops.api.result.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.config.ArtifactClientProperties;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.exception.RunNotFoundException;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.application.port.out.ArtifactUrlSigner;
import com.qualityops.api.result.domain.ArtifactAvailability;
import com.qualityops.api.result.domain.ArtifactType;
import com.qualityops.api.result.domain.TestResultArtifact;
import com.qualityops.api.result.exception.ArtifactNotFoundException;
import com.qualityops.api.result.exception.ArtifactRunNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    @Mock private ArtifactMetadataRepository repo;
    @Mock private GetRunUseCase getRunUseCase;
    @Mock private ArtifactUrlSigner signer;
    @Mock private ObjectProvider<ArtifactUrlSigner> signerProvider;

    private ArtifactService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID caseId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(signerProvider.getIfAvailable()).thenReturn(signer);
        var props = new ArtifactClientProperties(true, "http://minio:9000", "b", "k", "s",
            "us-east-1", true, Duration.ofSeconds(5000));   // 5000s → clamps to 900s
        service = new ArtifactService(repo, getRunUseCase, signerProvider, props);
    }

    @Test
    void get_availableArtifactWithMatchingKeyPrefix_signsWithClampedTtl() {
        var artifact = available("org/" + orgId + "/run/" + runId + "/case/" + caseId
            + "/attempt/0/SCREENSHOT/s.png");
        when(repo.findByIdAndOrgId(artifact.id(), orgId)).thenReturn(Optional.of(artifact));
        when(signer.sign(any(), any())).thenReturn(
            new ArtifactUrlSigner.PresignedUrl("https://minio/presigned", Instant.now().plusSeconds(900)));

        var response = service.get(artifact.id(), orgId);

        var ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(signer).sign(eq(artifact.storageKey()), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(900));
        assertThat(response.url()).isEqualTo("https://minio/presigned");
        assertThat(response.status()).isEqualTo("AVAILABLE");
    }

    @Test
    void get_availableArtifactWhoseKeyPrefixIsAnotherOrg_is404AndNeverSigns() {
        var foreign = available("org/" + UUID.randomUUID() + "/run/" + runId
            + "/case/" + caseId + "/attempt/0/SCREENSHOT/s.png");
        when(repo.findByIdAndOrgId(foreign.id(), orgId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.get(foreign.id(), orgId))
            .isInstanceOf(ArtifactNotFoundException.class);
        verify(signer, never()).sign(any(), any());
    }

    @Test
    void get_unknownId_throwsArtifactNotFound() {
        var id = UUID.randomUUID();
        when(repo.findByIdAndOrgId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, orgId)).isInstanceOf(ArtifactNotFoundException.class);
    }

    @Test
    void get_unavailableArtifact_returnsNullUrl() {
        var unavailable = new TestResultArtifact(UUID.randomUUID(), orgId, runId, caseId, 0,
            ArtifactType.TRACE, null, null, null, ArtifactAvailability.UNAVAILABLE,
            "store-unreachable", Instant.now());
        when(repo.findByIdAndOrgId(unavailable.id(), orgId)).thenReturn(Optional.of(unavailable));

        var response = service.get(unavailable.id(), orgId);

        assertThat(response.url()).isNull();
        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.unavailableReason()).isEqualTo("store-unreachable");
        verify(signer, never()).sign(any(), any());
    }

    @Test
    void listForRun_runNotInCallerOrg_throwsArtifactRunNotFound() {
        when(getRunUseCase.getDomain(runId, orgId)).thenThrow(new RunNotFoundException("nope"));

        assertThatThrownBy(() -> service.listForRun(runId, orgId, 1, 20))
            .isInstanceOf(ArtifactRunNotFoundException.class);
        verify(repo, never()).findAllByRunIdAndOrgId(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void listForRun_signsEachAvailableRow() {
        when(getRunUseCase.getDomain(runId, orgId)).thenReturn(null);
        var a1 = available("org/" + orgId + "/run/" + runId + "/case/" + caseId + "/attempt/0/SCREENSHOT/a.png");
        when(repo.findAllByRunIdAndOrgId(runId, orgId, 1, 20))
            .thenReturn(new PageResult<>(java.util.List.of(a1), 1, 20, 1L));
        when(signer.sign(any(), any())).thenReturn(
            new ArtifactUrlSigner.PresignedUrl("https://minio/x", Instant.now().plusSeconds(300)));

        var page = service.listForRun(runId, orgId, 1, 20);

        assertThat(page.items()).singleElement().satisfies(r ->
            assertThat(r.url()).isEqualTo("https://minio/x"));
    }

    private TestResultArtifact available(String key) {
        return new TestResultArtifact(UUID.randomUUID(), orgId, runId, caseId, 0,
            ArtifactType.SCREENSHOT, key, "image/png", 2048L, ArtifactAvailability.AVAILABLE, null, Instant.now());
    }
}
