package com.qualityops.api.environment.dto;

import com.qualityops.api.environment.domain.EnvironmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateEnvironmentRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @URL @Size(max = 2048) String baseUrl,
    @NotNull EnvironmentType type
) {}
