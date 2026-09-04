package com.qualityops.api.result.adapter.out.persistence;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.application.port.out.ArtifactMetadataRepository;
import com.qualityops.api.result.domain.TestResultArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class ArtifactMetadataRepositoryAdapter implements ArtifactMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(ArtifactMetadataRepositoryAdapter.class);

    private final TestResultArtifactJpaRepository jpa;

    ArtifactMetadataRepositoryAdapter(TestResultArtifactJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public void upsertForCase(UUID orgId, UUID runId, UUID testCaseId, int attemptEpoch,
                              List<TestResultArtifact> artifacts) {
        if (attemptEpoch < jpa.currentMaxEpoch(orgId, runId, testCaseId)) {
            log.debug("Ignoring stale artifact set for run {} case {} epoch {} (current max higher)",
                runId, testCaseId, attemptEpoch);
            return;
        }
        for (var a : artifacts) {
            jpa.upsertOne(orgId, runId, testCaseId, attemptEpoch,
                a.artifactType().name(), a.storageKey(), a.contentType(), a.sizeBytes(),
                a.status().name(), a.unavailableReason());
        }
        jpa.deleteLowerEpochs(orgId, runId, testCaseId, attemptEpoch);
    }

    @Override
    public PageResult<TestResultArtifact> findAllByRunIdAndOrgId(UUID runId, UUID orgId, int page, int size) {
        int safePage = page < 1 ? 1 : page;
        int safeSize = Math.min(Math.max(size < 1 ? 20 : size, 1), 100);
        var result = jpa.findAllByRunIdAndOrgId(runId, orgId, PageRequest.of(safePage - 1, safeSize));
        return new PageResult<>(
            result.getContent().stream().map(ArtifactMetadataRepositoryAdapter::toDomain).toList(),
            safePage,
            safeSize,
            result.getTotalElements()
        );
    }

    @Override
    public Optional<TestResultArtifact> findByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgId(id, orgId).map(ArtifactMetadataRepositoryAdapter::toDomain);
    }

    private static TestResultArtifact toDomain(TestResultArtifactEntity e) {
        return new TestResultArtifact(
            e.getId(), e.getOrgId(), e.getRunId(), e.getTestCaseId(), e.getAttemptEpoch(),
            e.getArtifactType(), e.getStorageKey(), e.getContentType(), e.getSizeBytes(),
            e.getStatus(), e.getUnavailableReason(), e.getCreatedAt());
    }
}
