package com.qualityops.api.webhook.adapter.out.persistence;

import com.qualityops.api.webhook.application.port.out.WebhookEndpointRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class WebhookEndpointRepositoryAdapter implements WebhookEndpointRepository {

    private final WebhookEndpointJpaRepository jpa;

    WebhookEndpointRepositoryAdapter(WebhookEndpointJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public WebhookEndpointRow create(UUID orgId, UUID projectId, String url, String secret,
                                     boolean enabled, UUID createdBy) {
        var saved = jpa.save(WebhookEndpointEntity.create(orgId, projectId, url, secret, enabled, createdBy));
        return toRow(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookEndpointRow> listForProject(UUID orgId, UUID projectId) {
        return jpa.findByOrgIdAndProjectIdOrderByCreatedAtAsc(orgId, projectId).stream()
            .map(WebhookEndpointRepositoryAdapter::toRow)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WebhookEndpointRow> findById(UUID id, UUID orgId) {
        return jpa.findByIdAndOrgId(id, orgId).map(WebhookEndpointRepositoryAdapter::toRow);
    }

    @Override
    @Transactional
    public boolean deleteByIdAndOrgId(UUID id, UUID orgId) {
        return jpa.deleteByIdAndOrgId(id, orgId) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WebhookEndpointRow> findEnabledForOrgAndProject(UUID orgId, UUID projectId) {
        return jpa.findEnabledForOrgAndProject(orgId, projectId).stream()
            .map(WebhookEndpointRepositoryAdapter::toRow)
            .toList();
    }

    private static WebhookEndpointRow toRow(WebhookEndpointEntity e) {
        return new WebhookEndpointRow(e.getId(), e.getOrgId(), e.getProjectId(), e.getUrl(),
            e.getSecret(), e.isEnabled(), e.getCreatedAt());
    }
}
