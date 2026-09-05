package com.qualityops.worker.execution.application.port.out;

import com.qualityops.worker.execution.domain.ArtifactUpload;
import com.qualityops.worker.execution.domain.StoredArtifact;
import com.qualityops.worker.execution.exception.ArtifactStoreException;

/** Write-only durable artifact storage. No get / list / presign — the Worker
 *  only ever writes; presigning is API-side (ADR-005 §1.1, §1.5). No
 *  S3/MinIO/AWS type crosses this port. */
public interface ArtifactStoragePort {

    StoredArtifact put(ArtifactUpload upload) throws ArtifactStoreException;
}
