package com.qualityops.api.execution.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** ADR-007 §4. The {@code @Max(1000)} sanity bound lives only here (V15's CHECK is
 *  just {@code > 0}); raising it is a one-line DTO change, not a migration. */
public record SetRunConcurrencyRequest(
        @NotNull @Min(1) @Max(1000) Integer maxActiveRuns) {}
