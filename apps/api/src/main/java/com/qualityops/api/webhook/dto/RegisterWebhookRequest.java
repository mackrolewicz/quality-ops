package com.qualityops.api.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** ADR-007 §6.2. {@code enabled} nullable — absent means enabled. */
public record RegisterWebhookRequest(
        @NotBlank @Size(max = 2048) String url,
        @NotBlank @Size(min = 16, max = 255) String secret,
        Boolean enabled) {}
