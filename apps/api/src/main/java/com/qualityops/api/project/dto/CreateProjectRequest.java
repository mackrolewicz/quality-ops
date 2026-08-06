package com.qualityops.api.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description,
    @NotBlank @Size(max = 100)
    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "slug must be lowercase kebab-case")
    String slug
) {}
