package com.qualityops.api.result.application.port.out;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.domain.TestResultArtifact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Authoritative store of artifact metadata. The API is the sole writer;
 *  every read is org-scoped. */
public interface ArtifactMetadataRepository {

    /**
     * Idempotent, epoch-monotone upsert for one case's artifacts:
     * <ul>
     *   <li>a chunk whose {@code attemptEpoch} is below the case's current max is ignored;</li>
     *   <li>each reference is inserted-or-updated on
     *       {@code (run_id, test_case_id, attempt_epoch, artifact_type)};</li>
     *   <li>rows for the same case at a lower {@code attempt_epoch} are deleted.</li>
     * </ul>
     */
    void upsertForCase(UUID orgId, UUID runId, UUID testCaseId, int attemptEpoch,
                       List<TestResultArtifact> artifacts);

    PageResult<TestResultArtifact> findAllByRunIdAndOrgId(UUID runId, UUID orgId, int page, int size);

    Optional<TestResultArtifact> findByIdAndOrgId(UUID id, UUID orgId);
}
