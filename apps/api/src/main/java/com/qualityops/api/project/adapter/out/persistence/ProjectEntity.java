package com.qualityops.api.project.adapter.out.persistence;

import com.qualityops.api.project.domain.Project;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects")
class ProjectEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String slug;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected ProjectEntity() {}

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    Project toDomain() {
        return new Project(id, orgId, name, description, slug, createdBy, createdAt, updatedAt, deletedAt);
    }

    static ProjectEntity fromDomain(Project project) {
        var entity = new ProjectEntity();
        entity.id = project.id();
        entity.orgId = project.orgId();
        entity.name = project.name();
        entity.description = project.description();
        entity.slug = project.slug();
        entity.createdBy = project.createdBy();
        entity.deletedAt = project.deletedAt();
        return entity;
    }
}
