package com.miniurl.identity.repository;

import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);

    // Find users by status
    List<User> findByStatus(UserStatus status);
    List<User> findByStatusOrderByCreatedAtDesc(UserStatus status);

    // Find users by role
    List<User> findByRoleId(Long roleId);

    // Search users
    List<User> findByFirstNameContainingOrLastNameContainingOrEmailContaining(
        String firstName, String lastName, String email);

    // Count active users
    long countByStatus(UserStatus status);

    // Pagination support
    Page<User> findAll(Pageable pageable);
    Page<User> findByStatus(UserStatus status, Pageable pageable);
}
