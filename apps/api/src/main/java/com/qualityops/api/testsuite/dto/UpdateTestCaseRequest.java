package com.qualityops.api.testsuite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateTestCaseRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description,
    @NotNull Integer orderIndex
) {}
