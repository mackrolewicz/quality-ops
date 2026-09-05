package com.qualityops.worker.execution.domain;

import com.qualityops.events.ArtifactType;

import java.util.UUID;

/** Identity of one artifact. The storage adapter derives the object key from
 *  this — org-first, path-addressed (ADR-005 §1.2):
 *  {@code org/<orgId>/run/<runId>/execution/<executionId>/case/<testCaseId>/attempt/<attemptEpoch>/<type>/<filename>}. */
public record ArtifactRef(
        UUID orgId, UUID runId, UUID executionId, UUID testCaseId,
        int attemptEpoch, ArtifactType type, String filename) {

    public String storageKey() {
        return "org/" + orgId
            + "/run/" + runId
            + "/execution/" + executionId
            + "/case/" + testCaseId
            + "/attempt/" + attemptEpoch
            + "/" + type.name()
            + "/" + filename;
    }
}
