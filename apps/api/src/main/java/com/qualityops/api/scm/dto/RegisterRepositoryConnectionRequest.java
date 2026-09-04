package com.qualityops.api.scm.dto;

import com.qualityops.events.RepositoryProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** ADR-009 §11. {@code host} is optional (defaults to the provider's public
 *  host); {@code credentialRef} is the opaque resolver key only — never a token. */
public record RegisterRepositoryConnectionRequest(
    @NotNull RepositoryProvider provider,
    @Size(max = 255) String host,
    @NotBlank @Size(max = 512) String ownerPath,
    @NotBlank @Size(max = 255) String repoName,
    @Size(max = 255) String defaultRef,
    @Pattern(regexp = "[A-Z0-9_]{1,64}", message = "credentialRef must match [A-Z0-9_]{1,64}")
    String credentialRef
) {}
