package com.miniurl.identity.service;

import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import com.miniurl.identity.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Optional;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Try to find by username first, then by email
        Optional<User> user = userRepository.findByUsername(username)
            .or(() -> userRepository.findByEmail(username));

        if (user.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        User userData = user.get();

        // Check if user is active
        if (userData.getStatus() != UserStatus.ACTIVE) {
            throw new UsernameNotFoundException("User account is not active: " + username);
        }

        // Check if account is locked - throw LockedException so Spring Security knows it's locked
        if (userData.isAccountLocked()) {
            throw new LockedException("Account temporarily locked due to too many failed login attempts: " + username);
        }

        // Get role authority
        String roleName = userData.getRole() != null ? userData.getRole().getName() : "USER";

        return new org.springframework.security.core.userdetails.User(
                userData.getUsername(),
                userData.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + roleName))
        );
    }

    /**
     * Load user with full details including token version
     */
    public User loadUserEntityByUsername(String username) throws UsernameNotFoundException {
        Optional<User> user = userRepository.findByUsername(username)
            .or(() -> userRepository.findByEmail(username));

        if (user.isEmpty()) {
            throw new UsernameNotFoundException("User not found: " + username);
        }

        User userData = user.get();

        if (userData.getStatus() != UserStatus.ACTIVE) {
            throw new UsernameNotFoundException("User account is not active: " + username);
        }

        if (userData.isAccountLocked()) {
            throw new UsernameNotFoundException("Account temporarily locked: " + username);
        }

        return userData;
    }
}
