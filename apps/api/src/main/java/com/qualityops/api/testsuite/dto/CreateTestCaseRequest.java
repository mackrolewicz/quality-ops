package com.qualityops.api.testsuite.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Objects;
import java.util.stream.Stream;

public record CreateTestCaseRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 2000) String description,
    Integer orderIndex,
    @Valid ApiRequestPayload apiRequest,
    @Valid BrowserTestPayload browserTest,
    @Valid RepoTestPayload repoTest
) {
    /** Convenience — no repository spec. Keeps pre-2F call sites compiling. */
    public CreateTestCaseRequest(String name, String description, Integer orderIndex,
                                 ApiRequestPayload apiRequest, BrowserTestPayload browserTest) {
        this(name, description, orderIndex, apiRequest, browserTest, null);
    }

    @AssertTrue(message = "A test case may define at most one of apiRequest, browserTest, repoTest")
    boolean isSpecMutuallyExclusive() {
        return Stream.of(apiRequest, browserTest, repoTest).filter(Objects::nonNull).count() <= 1;
    }
}
