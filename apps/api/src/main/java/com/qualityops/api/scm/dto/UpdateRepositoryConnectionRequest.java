package com.qualityops.api.scm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** ADR-009 §11. {@code provider} is immutable and not updatable. */
public record UpdateRepositoryConnectionRequest(
    @Size(max = 255) String host,
    @NotBlank @Size(max = 512) String ownerPath,
    @NotBlank @Size(max = 255) String repoName,
    @NotBlank @Size(max = 255) String defaultRef,
    @Pattern(regexp = "[A-Z0-9_]{1,64}", message = "credentialRef must match [A-Z0-9_]{1,64}")
    String credentialRef
) {}
