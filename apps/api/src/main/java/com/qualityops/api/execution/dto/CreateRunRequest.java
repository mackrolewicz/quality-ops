package com.qualityops.api.execution.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

public record CreateRunRequest(
    @NotNull UUID projectId,
    @NotNull UUID suiteId,
    @NotNull UUID environmentId,
    @Pattern(regexp = "HIGH|NORMAL|LOW", message = "priority must be HIGH, NORMAL or LOW") String priority
) {}
