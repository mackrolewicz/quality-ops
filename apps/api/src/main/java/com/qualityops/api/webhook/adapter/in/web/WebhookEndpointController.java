package com.qualityops.api.webhook.adapter.in.web;

import com.qualityops.api.common.ApiResponse;
import com.qualityops.api.config.UserPrincipal;
import com.qualityops.api.webhook.application.port.in.ManageWebhookEndpointsUseCase;
import com.qualityops.api.webhook.dto.RegisterWebhookRequest;
import com.qualityops.api.webhook.dto.WebhookEndpointResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** ADR-007 §6.2 — OWNER/ADMIN CRUD over their own project's signed-webhook
 *  endpoints. {@code secret} is write-only; responses mask it as
 *  {@code secretSet: true}. */
@RestController
@Tag(name = "Webhooks", description = "Signed run-completion webhook endpoints (ADR-007 §6)")
public class WebhookEndpointController {

    private final ManageWebhookEndpointsUseCase webhooks;

    public WebhookEndpointController(ManageWebhookEndpointsUseCase webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/v1/projects/{projectId}/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Register a signed-webhook endpoint for a project")
    public ApiResponse<WebhookEndpointResponse> register(@PathVariable UUID projectId,
            @Valid @RequestBody RegisterWebhookRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(webhooks.register(projectId, user.orgId(), request, user.userId()));
    }

    @GetMapping("/api/v1/projects/{projectId}/webhooks")
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "List a project's webhook endpoints (secret masked)")
    public ApiResponse<List<WebhookEndpointResponse>> list(@PathVariable UUID projectId,
            @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.success(webhooks.list(projectId, user.orgId()));
    }

    @DeleteMapping("/api/v1/webhooks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('OWNER','ADMIN')")
    @Operation(summary = "Remove a webhook endpoint")
    public void delete(@PathVariable UUID id, @AuthenticationPrincipal UserPrincipal user) {
        webhooks.delete(id, user.orgId());
    }
}
