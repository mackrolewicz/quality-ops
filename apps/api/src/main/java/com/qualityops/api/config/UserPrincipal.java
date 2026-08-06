package com.qualityops.api.config;

import com.qualityops.api.identity.domain.Role;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public record UserPrincipal(
    UUID userId,
    UUID orgId,
    Role role,
    Collection<GrantedAuthority> authorities
) implements UserDetails {

    public UserPrincipal(UUID userId, UUID orgId, Role role) {
        this(userId, orgId, role, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return "";
    }

    @Override
    public String getUsername() {
        return userId.toString();
    }
}
