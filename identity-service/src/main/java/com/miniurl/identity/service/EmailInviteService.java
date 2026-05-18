package com.miniurl.identity.service;

import com.miniurl.dto.NotificationEvent;
import com.miniurl.dto.PagedResponse;
import com.miniurl.identity.entity.EmailInvite;
import com.miniurl.identity.entity.EmailInvite.InviteStatus;
import com.miniurl.identity.entity.User;
import com.miniurl.exception.UnauthorizedException;
import com.miniurl.identity.repository.EmailInviteRepository;
import com.miniurl.identity.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for managing email invitations.
 */
@Service
public class EmailInviteService {

    private static final Logger logger = LoggerFactory.getLogger(EmailInviteService.class);

    private final EmailInviteRepository emailInviteRepository;
    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public EmailInviteService(EmailInviteRepository emailInviteRepository,
                               KafkaTemplate<String, NotificationEvent> kafkaTemplate,
                               UserRepository userRepository,
                               PasswordEncoder passwordEncoder) {
        this.emailInviteRepository = emailInviteRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create and send an email invitation.
     */
    @Transactional
    public EmailInvite createInvite(String email, String invitedByUsername) {
        // Validate email
        if (email == null || email.trim().isEmpty()) {
            throw new UnauthorizedException("Email is required");
        }

        email = email.trim().toLowerCase();

        // Check if user already exists with this email
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            throw new UnauthorizedException("A user with this email already exists");
        }

        // Check if email already has an active invite (check all invites for this email)
        List<EmailInvite> existingInvites = emailInviteRepository.findAllByEmail(email);
        for (EmailInvite existing : existingInvites) {
            if (existing.getStatus() == InviteStatus.PENDING && !existing.isExpired()) {
                throw new UnauthorizedException("An active invite already exists for this email");
            }
        }

        // Generate unique token
        String token = generateInviteToken();

        // Create invite
        EmailInvite invite = new EmailInvite(email, token, invitedByUsername);
        emailInviteRepository.save(invite);

        // Send invitation email (uses app.base-url from configuration)
        try {
            NotificationEvent event = NotificationEvent.builder()
                .eventType("INVITE")
                .toEmail(email)
                .payload(java.util.Map.of(
                    "token", token,
                    "invitedBy", invitedByUsername
                ))
                .build();
            kafkaTemplate.send("notification-topic", event);
            logger.info("Email invite event sent to: {} by: {}", email, invitedByUsername);
        } catch (Exception e) {
            logger.error("Failed to send invite email event to {}: {}", email, e.getMessage());
            // Don't fail the operation if email sending fails - invite is still created
        }

        return invite;
    }

    /**
     * Get all invites.
     */
    @Transactional(readOnly = true)
    public List<EmailInvite> getAllInvites() {
        return emailInviteRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Get all invites with pagination and sorting.
     */
    @Transactional(readOnly = true)
    public PagedResponse<EmailInvite> getAllInvites(int page, int size, String sortBy, String sortDirection) {
        return getAllInvites(page, size, sortBy, sortDirection, null);
    }

    /**
     * Get all invites with pagination, sorting, and optional search.
     */
    @Transactional(readOnly = true)
    public PagedResponse<EmailInvite> getAllInvites(int page, int size, String sortBy, String sortDirection, String searchEmail) {
        // Validate sort field
        String validSortBy = validateSortField(sortBy);

        // Create sort
        Sort sort = "asc".equalsIgnoreCase(sortDirection) ?
            Sort.by(validSortBy).ascending() :
            Sort.by(validSortBy).descending();

        PageRequest pageRequest = PageRequest.of(page, size, sort);

        Page<EmailInvite> invitePage;
        
        // Handle search query
        if (searchEmail != null && !searchEmail.isEmpty()) {
            List<EmailInvite> allInvites = emailInviteRepository.findAllByOrderByCreatedAtDesc();
            // Filter by email search
            List<EmailInvite> filteredInvites = allInvites.stream()
                .filter(invite -> invite.getEmail().toLowerCase().contains(searchEmail.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
            
            // Apply manual pagination for search results
            int totalElements = filteredInvites.size();
            int start = Math.min(page * size, totalElements);
            int end = Math.min(start + size, totalElements);
            List<EmailInvite> paginatedInvites = start < end ? filteredInvites.subList(start, end) : java.util.List.of();
            
            invitePage = new org.springframework.data.domain.PageImpl<>(paginatedInvites, pageRequest, totalElements);
        } else {
            invitePage = emailInviteRepository.findAll(pageRequest);
        }

        return PagedResponse.<EmailInvite>builder()
            .content(invitePage.getContent())
            .page(page)
            .size(size)
            .totalElements(invitePage.getTotalElements())
            .sortBy(validSortBy)
            .sortDirection(sortDirection)
            .build();
    }

    /**
     * Get pending invites.
     */
    @Transactional(readOnly = true)
    public List<EmailInvite> getPendingInvites() {
        return emailInviteRepository.findByStatusOrderByCreatedAtDesc(InviteStatus.PENDING);
    }

    /**
     * Get accepted invites.
     */
    @Transactional(readOnly = true)
    public List<EmailInvite> getAcceptedInvites() {
        return emailInviteRepository.findByStatus(InviteStatus.ACCEPTED);
    }

    /**
     * Get invite by ID.
     */
    @Transactional(readOnly = true)
    public EmailInvite getById(Long id) {
        return emailInviteRepository.findById(id).orElse(null);
    }

    /**
     * Validate an invitation token without accepting it.
     * Used during signup to verify the token is valid before registration.
     * @param token Invitation token
     * @return EmailInvite if valid
     * @throws UnauthorizedException if token is invalid, expired, revoked, or already used
     */
    @Transactional(readOnly = true)
    public EmailInvite validateInvite(String token) {
        EmailInvite invite = emailInviteRepository.findByToken(token)
                .orElseThrow(() -> new UnauthorizedException("Invalid invite token"));

        // Check if expired
        if (invite.isExpired()) {
            throw new UnauthorizedException("This invite has expired");
        }

        // Check if revoked
        if (invite.getStatus() == InviteStatus.REVOKED) {
            throw new UnauthorizedException("This invite has been revoked");
        }

        // Check if already accepted
        if (invite.getStatus() == InviteStatus.ACCEPTED) {
            throw new UnauthorizedException("This invite has already been used");
        }

        return invite;
    }

    /**
     * Validate and accept an invite.
     */
    @Transactional
    public EmailInvite acceptInvite(String token) {
        EmailInvite invite = emailInviteRepository.findByToken(token)
                .orElseThrow(() -> new UnauthorizedException("Invalid invite token"));

        // Check if already accepted
        if (invite.getStatus() == InviteStatus.ACCEPTED) {
            throw new UnauthorizedException("This invite has already been used");
        }

        // Check if expired
        if (invite.isExpired()) {
            invite.setStatus(InviteStatus.EXPIRED);
            emailInviteRepository.save(invite);
            throw new UnauthorizedException("This invite has expired");
        }

        // Check if revoked
        if (invite.getStatus() == InviteStatus.REVOKED) {
            throw new UnauthorizedException("This invite has been revoked");
        }

        // Accept the invite
        invite.accept();
        emailInviteRepository.save(invite);

        logger.info("Invite accepted for email: {}", invite.getEmail());
        return invite;
    }

    /**
     * Revoke an invite.
     */
    @Transactional
    public EmailInvite revokeInvite(Long inviteId) {
        EmailInvite invite = emailInviteRepository.findById(inviteId)
                .orElseThrow(() -> new UnauthorizedException("Invite not found"));

        if (invite.getStatus() == InviteStatus.ACCEPTED) {
            throw new UnauthorizedException("Cannot revoke an accepted invite");
        }

        invite.revoke();
        emailInviteRepository.save(invite);

        logger.info("Invite revoked for email: {}", invite.getEmail());
        return invite;
    }

    /**
     * Check if an email has a valid pending invite.
     */
    @Transactional(readOnly = true)
    public boolean hasValidInvite(String email) {
        return emailInviteRepository.findFirstByEmailOrderByCreatedAtDesc(email)
                .filter(invite -> invite.getStatus() == InviteStatus.PENDING && !invite.isExpired())
                .isPresent();
    }

    /**
     * Generate a unique invite token.
     */
    private String generateInviteToken() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder token = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            token.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return token.toString();
    }

    /**
     * Validate sort field for email invites.
     */
    private String validateSortField(String sortBy) {
        if (sortBy == null || sortBy.trim().isEmpty()) {
            return "createdAt";
        }
        
        Set<String> allowedFields = Set.of("id", "email", "status", "createdAt", "expiresAt", "invitedByUsername");
        String field = sortBy.trim();
        
        if (!allowedFields.contains(field)) {
            return "createdAt";
        }
        
        return field;
    }
}
