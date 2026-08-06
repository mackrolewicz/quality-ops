package com.qualityops.api.testsuite.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTestCaseRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description,
    Integer orderIndex
) {}
