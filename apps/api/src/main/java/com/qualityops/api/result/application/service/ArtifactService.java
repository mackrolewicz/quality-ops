package com.qualityops.api.result.application.service;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.config.ArtifactClientProperties;
import com.qualityops.api.execution.application.port.in.GetRunUseCase;
import com.qualityops.api.execution.exception.RunNotFoundException;
import com.qualityops.api.result.application.port.in.GetArtifactUseCase;
import com.qualityops.api.result.application.port.in.ListRunArtifactsUseCase;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.application.port.out.ArtifactUrlSigner;
import com.qualityops.api.result.domain.ArtifactAvailability;
import com.qualityops.api.result.domain.TestResultArtifact;
import com.qualityops.api.result.dto.ArtifactResponse;
import com.qualityops.api.result.exception.ArtifactNotFoundException;
import com.qualityops.api.result.exception.ArtifactRunNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ArtifactService implements ListRunArtifactsUseCase, GetArtifactUseCase {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactMetadataRepository artifacts;
    private final GetRunUseCase getRunUseCase;
    private final ObjectProvider<ArtifactUrlSigner> signerProvider;
    private final Duration presignTtl;

    public ArtifactService(ArtifactMetadataRepository artifacts,
                           GetRunUseCase getRunUseCase,
                           ObjectProvider<ArtifactUrlSigner> signerProvider,
                           ArtifactClientProperties props) {
        this.artifacts = artifacts;
        this.getRunUseCase = getRunUseCase;
        this.signerProvider = signerProvider;
        this.presignTtl = props.effectivePresignTtl();
    }

    @Override
    public PageResult<ArtifactResponse> listForRun(UUID runId, UUID orgId, int page, int size) {
        try {
            getRunUseCase.getDomain(runId, orgId);   // org-scoped — foreign/unknown ⇒ 404
        } catch (RunNotFoundException e) {
            throw new ArtifactRunNotFoundException();
        }
        var pageResult = artifacts.findAllByRunIdAndOrgId(runId, orgId, page, size);
        return new PageResult<>(
            pageResult.items().stream().map(a -> toResponse(a, orgId)).toList(),
            pageResult.page(), pageResult.size(), pageResult.total());
    }

    @Override
    public ArtifactResponse get(UUID id, UUID orgId) {
        var artifact = artifacts.findByIdAndOrgId(id, orgId).orElseThrow(ArtifactNotFoundException::new);
        // Defence in depth: the key's org/<orgId>/ segment must also match.
        if (artifact.status() == ArtifactAvailability.AVAILABLE && !keyBelongsToOrg(artifact, orgId)) {
            throw new ArtifactNotFoundException();
        }
        return toResponse(artifact, orgId);
    }

    private ArtifactResponse toResponse(TestResultArtifact a, UUID orgId) {
        String url = null;
        java.time.Instant expiresAt = null;
        if (a.status() == ArtifactAvailability.AVAILABLE && keyBelongsToOrg(a, orgId)) {
            var signer = signerProvider.getIfAvailable();
            if (signer != null) {
                var presigned = signer.sign(a.storageKey(), presignTtl);
                url = presigned.url();
                expiresAt = presigned.expiresAt();
            }
        } else if (a.status() == ArtifactAvailability.AVAILABLE) {
            log.warn("Artifact {} key prefix does not match org {} — not signing", a.id(), orgId);
        }
        return new ArtifactResponse(a.id(), a.testCaseId(), a.attemptEpoch(),
            a.artifactType().name(), a.contentType(), a.sizeBytes(),
            a.status().name(), a.unavailableReason(), url, expiresAt);
    }

    private static boolean keyBelongsToOrg(TestResultArtifact a, UUID orgId) {
        return a.storageKey() != null && a.storageKey().startsWith("org/" + orgId + "/");
    }
}
