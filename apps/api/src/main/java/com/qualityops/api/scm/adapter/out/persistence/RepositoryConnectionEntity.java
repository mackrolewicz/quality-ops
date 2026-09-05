package com.qualityops.api.scm.adapter.out.persistence;

import com.qualityops.api.scm.domain.RepositoryConnection;
import com.qualityops.events.RepositoryProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** ADR-009 §3 (V22). {@code provider} is stored as {@code VARCHAR + CHECK} — a
 *  String enum, not a PG enum type. {@code credential_ref} is the opaque resolver
 *  key only; no token column exists. */
@Entity
@Table(name = "repository_connection")
class RepositoryConnectionEntity {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 16)
    private RepositoryProvider provider;

    @Column(name = "host", nullable = false, length = 255)
    private String host;

    @Column(name = "owner_path", nullable = false, length = 512)
    private String ownerPath;

    @Column(name = "repo_name", nullable = false, length = 255)
    private String repoName;

    @Column(name = "default_ref", nullable = false, length = 255)
    private String defaultRef;

    @Column(name = "credential_ref", length = 64)
    private String credentialRef;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected RepositoryConnectionEntity() {}

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

    static RepositoryConnectionEntity fromDomain(RepositoryConnection c) {
        var e = new RepositoryConnectionEntity();
        e.id = c.id();
        e.orgId = c.orgId();
        e.projectId = c.projectId();
        e.provider = c.provider();
        e.host = c.host();
        e.ownerPath = c.ownerPath();
        e.repoName = c.repoName();
        e.defaultRef = c.defaultRef();
        e.credentialRef = c.credentialRef();
        e.createdBy = c.createdBy();
        e.createdAt = c.createdAt();
        e.updatedAt = c.updatedAt();
        e.deletedAt = c.deletedAt();
        return e;
    }

    RepositoryConnection toDomain() {
        return new RepositoryConnection(id, orgId, projectId, provider, host, ownerPath, repoName,
            defaultRef, credentialRef, createdBy, createdAt, updatedAt, deletedAt);
    }
}
