package com.miniurl.identity.entity;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collections;
import java.util.List;

/**
 * UserDetails implementation for User entity.
 */
public class UserPrincipal implements UserDetails {

    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    /**
     * Returns the underlying User entity for extracting claims (userId, roles, etc.).
     */
    public User getUser() {
        return user;
    }

    /**
     * Convenience method to get the user's database ID for JWT claims.
     */
    public Long getUserId() {
        return user.getId();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public boolean isAccountNonExpired() {
        // Account is not expired if status is ACTIVE
        return user.getStatus() == UserStatus.ACTIVE;
    }

    @Override
    public boolean isAccountNonLocked() {
        // Account is not locked if lockoutTime is null or in the past
        return !user.isAccountLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getStatus() == UserStatus.ACTIVE;
    }

    @Override
    public List<? extends SimpleGrantedAuthority> getAuthorities() {
        if (user.getRole() != null) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName()));
        }
        return Collections.emptyList();
    }
}
