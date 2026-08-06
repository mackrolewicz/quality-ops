package com.qualityops.api.identity.adapter.out.persistence;

import com.qualityops.api.identity.domain.Role;
import com.qualityops.api.identity.domain.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class UserEntity {

    @Id
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(nullable = false)
    private String email;

    @Column(name = "hashed_password", nullable = false)
    private String hashedPassword;

    // @JdbcTypeCode(NAMED_ENUM) tells Hibernate 6 this is a PostgreSQL named enum,
    // preventing a type-mismatch during ddl-auto:validate.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role")
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected UserEntity() {}

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    User toDomain() {
        return new User(id, orgId, email, hashedPassword, role, createdAt);
    }

    static UserEntity fromDomain(User user) {
        var entity = new UserEntity();
        entity.id = user.id();
        entity.orgId = user.orgId();
        entity.email = user.email();
        entity.hashedPassword = user.hashedPassword();
        entity.role = user.role();
        return entity;
    }
}
