package com.qualityops.api.webhook.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** ADR-007 §6.2. {@code secret} is plaintext at rest in 2D; never echoed over the
 *  API (masked as {@code secretSet: true}). {@code project_id} nullable — NULL
 *  means "all runs in this org". */
@Entity
@Table(name = "webhook_endpoint")
class WebhookEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "secret", nullable = false, length = 255)
    private String secret;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpointEntity() {}

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    UUID getProjectId() {
        return projectId;
    }

    String getUrl() {
        return url;
    }

    String getSecret() {
        return secret;
    }

    boolean isEnabled() {
        return enabled;
    }

    UUID getCreatedBy() {
        return createdBy;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    static WebhookEndpointEntity create(UUID orgId, UUID projectId, String url, String secret,
                                        boolean enabled, UUID createdBy) {
        var entity = new WebhookEndpointEntity();
        entity.orgId = orgId;
        entity.projectId = projectId;
        entity.url = url;
        entity.secret = secret;
        entity.enabled = enabled;
        entity.createdBy = createdBy;
        return entity;
    }
}
