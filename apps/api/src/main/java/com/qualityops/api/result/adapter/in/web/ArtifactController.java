package com.qualityops.api.result.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.result.application.port.in.GetArtifactUseCase;
import com.qualityops.api.result.application.port.in.ListRunArtifactsUseCase;
import com.qualityops.api.result.dto.ArtifactResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Test Artifacts", description = "Durable screenshots and traces for a run, via presigned URLs")
public class ArtifactController {

    private final ListRunArtifactsUseCase listRunArtifactsUseCase;
    private final GetArtifactUseCase getArtifactUseCase;

    public ArtifactController(ListRunArtifactsUseCase listRunArtifactsUseCase,
                              GetArtifactUseCase getArtifactUseCase) {
        this.listRunArtifactsUseCase = listRunArtifactsUseCase;
        this.getArtifactUseCase = getArtifactUseCase;
    }

    @GetMapping("/api/v1/runs/{runId}/artifacts")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "List stored artifacts for a run, each with a fresh presigned GET URL")
    public ApiResponse<?> list(@PathVariable UUID runId,
                               @RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "20") int size,
                               @AuthenticationPrincipal UserPrincipal user) {
        var result = listRunArtifactsUseCase.listForRun(runId, user.orgId(), page, size);
        return ApiResponse.success(result.items(),
            Map.of("page", result.page(), "pageSize", result.size(), "total", result.total()));
    }

    @GetMapping("/api/v1/artifacts/{id}")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN','MEMBER','VIEWER')")
    @Operation(summary = "Fetch one artifact's metadata + presigned URL, or 302 to the object")
    public ResponseEntity<?> get(@PathVariable UUID id,
                                 @RequestParam(defaultValue = "false") boolean redirect,
                                 @AuthenticationPrincipal UserPrincipal user) {
        ArtifactResponse artifact = getArtifactUseCase.get(id, user.orgId());
        if (redirect && artifact.url() != null) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(artifact.url())).build();
        }
        return ResponseEntity.ok(ApiResponse.success(artifact));
    }
}
