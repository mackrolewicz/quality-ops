package com.qualityops.api.identity.adapter.out.persistence;

import com.qualityops.api.identity.application.port.out.UserRepository;
import com.qualityops.api.identity.domain.User;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpa;

    UserRepositoryAdapter(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jpa.findByEmailAndDeletedAtIsNull(email).map(UserEntity::toDomain);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findByIdAndDeletedAtIsNull(id).map(UserEntity::toDomain);
    }

    @Override
    public User save(User user) {
        return jpa.save(UserEntity.fromDomain(user)).toDomain();
    }
}
