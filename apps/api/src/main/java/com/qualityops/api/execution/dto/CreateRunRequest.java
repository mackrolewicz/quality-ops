package com.qualityops.api.execution.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateRunRequest(
    @NotNull UUID projectId,
    @NotNull UUID suiteId,
    @NotNull UUID environmentId
) {}
