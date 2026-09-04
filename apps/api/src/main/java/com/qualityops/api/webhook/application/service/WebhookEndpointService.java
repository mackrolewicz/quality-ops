package com.qualityops.api.webhook.application.service;

import com.qualityops.api.audit.annotation.Audited;
import com.qualityops.api.audit.domain.AuditAction;
import com.qualityops.api.project.application.port.in.GetProjectUseCase;
import com.qualityops.api.webhook.application.port.in.ManageWebhookEndpointsUseCase;
import com.qualityops.api.webhook.application.port.out.WebhookEndpointRepository;
import com.qualityops.api.webhook.application.port.out.WebhookEndpointRepository.WebhookEndpointRow;
import com.qualityops.api.webhook.dto.RegisterWebhookRequest;
import com.qualityops.api.webhook.dto.WebhookEndpointResponse;
import com.qualityops.api.webhook.exception.WebhookEndpointNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** ADR-007 §6.2. Tenant-scoped CRUD: the project must belong to the caller's org
 *  ({@code getProjectUseCase.getDomain} throws -> 404). {@code secret} is stored
 *  raw and never echoed. NOT class-{@code @Transactional}: each operation is a
 *  single repository write/read (each adapter method is its own transaction), and
 *  {@code register} does a blocking DNS resolution in {@code WebhookUrlValidator}
 *  that must not run inside an open transaction. */
@Service
public class WebhookEndpointService implements ManageWebhookEndpointsUseCase {

    private final WebhookEndpointRepository repo;
    private final WebhookUrlValidator webhookUrlValidator;
    private final GetProjectUseCase getProjectUseCase;

    public WebhookEndpointService(WebhookEndpointRepository repo,
                                  WebhookUrlValidator webhookUrlValidator,
                                  GetProjectUseCase getProjectUseCase) {
        this.repo = repo;
        this.webhookUrlValidator = webhookUrlValidator;
        this.getProjectUseCase = getProjectUseCase;
    }

    @Override
    @Audited(action = AuditAction.WEBHOOK_ENDPOINT_REGISTER, targetType = "webhook_endpoint")
    public WebhookEndpointResponse register(UUID projectId, UUID orgId, RegisterWebhookRequest req,
                                            UUID userId) {
        getProjectUseCase.getDomain(projectId, orgId); // ownership check -> 404
        webhookUrlValidator.validate(req.url());
        boolean enabled = req.enabled() == null || req.enabled();
        var row = repo.create(orgId, projectId, req.url(), req.secret(), enabled, userId);
        return toResponse(row);
    }

    @Override
    public List<WebhookEndpointResponse> list(UUID projectId, UUID orgId) {
        getProjectUseCase.getDomain(projectId, orgId); // ownership check -> 404
        return repo.listForProject(orgId, projectId).stream().map(this::toResponse).toList();
    }

    @Override
    @Audited(action = AuditAction.WEBHOOK_ENDPOINT_DELETE, targetType = "webhook_endpoint")
    public void delete(UUID id, UUID orgId) {
        if (!repo.deleteByIdAndOrgId(id, orgId)) {
            throw new WebhookEndpointNotFoundException(id);
        }
    }

    private WebhookEndpointResponse toResponse(WebhookEndpointRow row) {
        return new WebhookEndpointResponse(row.id(), row.projectId(), row.url(), true,
            row.enabled(), row.createdAt());
    }
}
