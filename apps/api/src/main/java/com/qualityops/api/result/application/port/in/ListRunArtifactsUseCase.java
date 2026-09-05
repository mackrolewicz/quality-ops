package com.qualityops.api.result.application.port.in;

import com.qualityops.api.common.PageResult;
import com.qualityops.api.result.dto.ArtifactResponse;

import java.util.UUID;

public interface ListRunArtifactsUseCase {

    /** Every stored artifact for a run, each with a fresh short-TTL presigned GET
     *  URL. Throws {@code ArtifactRunNotFoundException} (→ 404) if the run is not
     *  in the caller's org. */
    PageResult<ArtifactResponse> listForRun(UUID runId, UUID orgId, int page, int size);
}
