package com.qualityops.api.config;

import com.qualityops.api.identity.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class JwtService {

    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    public String generateAccessToken(UUID userId, UUID orgId, Role role) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenExpiryMinutes(), ChronoUnit.MINUTES);

        // JJWT 0.12.x: use subject(), claim(), issuedAt(), expiration(), signWith()
        return Jwts.builder()
            .subject(userId.toString())
            .claim("org_id", orgId.toString())
            .claim("roles", List.of(role.name()))
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(signingKey())
            .compact();
    }

    public Claims validateAndParseToken(String token) {
        // JJWT 0.12.x: Jwts.parser() replaces the removed Jwts.parserBuilder()
        return Jwts.parser()
            .verifyWith(signingKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public UUID extractUserId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public UUID extractOrgId(Claims claims) {
        return UUID.fromString(claims.get("org_id", String.class));
    }

    public Role extractRole(Claims claims) {
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        return Role.valueOf(roles.get(0));
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
    }
}
