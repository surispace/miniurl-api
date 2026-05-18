package com.miniurl.identity.repository;

import com.miniurl.identity.entity.EmailInvite;
import com.miniurl.identity.entity.EmailInvite.InviteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository interface for EmailInvite entity operations.
 */
@Repository
public interface EmailInviteRepository extends JpaRepository<EmailInvite, Long> {

    /**
     * Find an invite by its token.
     */
    Optional<EmailInvite> findByToken(String token);

    /**
     * Find all invites by email (there can be multiple invites for same email).
     */
    List<EmailInvite> findAllByEmail(String email);

    /**
     * Find the most recent invite by email.
     */
    Optional<EmailInvite> findFirstByEmailOrderByCreatedAtDesc(String email);

    /**
     * Check if an invite exists for an email.
     */
    boolean existsByEmail(String email);

    /**
     * Find all invites ordered by creation date descending.
     */
    List<EmailInvite> findAllByOrderByCreatedAtDesc();

    /**
     * Find invites by status.
     */
    List<EmailInvite> findByStatus(InviteStatus status);

    /**
     * Find pending invites.
     */
    List<EmailInvite> findByStatusOrderByCreatedAtDesc(InviteStatus status);

    /**
     * Find all invites with pagination support.
     */
    Page<EmailInvite> findAll(Pageable pageable);
}
