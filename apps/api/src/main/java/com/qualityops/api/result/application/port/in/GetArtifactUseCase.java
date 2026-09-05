package com.qualityops.api.result.application.port.in;

import com.qualityops.api.result.dto.ArtifactResponse;

import java.util.UUID;

public interface GetArtifactUseCase {

    /** One artifact's metadata + a presigned GET URL. Throws
     *  {@code ArtifactNotFoundException} (→ 404) if the id is unknown or belongs
     *  to another org. */
    ArtifactResponse get(UUID id, UUID orgId);
}
