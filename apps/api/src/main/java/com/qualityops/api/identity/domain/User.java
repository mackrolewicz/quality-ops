package com.qualityops.api.identity.domain;

import java.time.Instant;
import java.util.UUID;

public record User(
    UUID id,
    UUID orgId,
    String email,
    String hashedPassword,
    Role role,
    Instant createdAt
) {}
