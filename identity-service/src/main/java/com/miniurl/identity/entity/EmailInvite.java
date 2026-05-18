package com.miniurl.identity.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * Entity representing an email invitation sent to potential users.
 * Used for invite-only user registration.
 */
@Entity
@Table(name = "email_invites")
public class EmailInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must be 255 characters or less")
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @NotBlank(message = "Token is required")
    @Size(max = 255, message = "Token must be 255 characters or less")
    @Column(name = "token", nullable = false, length = 255, unique = true)
    private String token;

    @Column(name = "status", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private InviteStatus status;

    @Size(max = 100, message = "Invited by must be 100 characters or less")
    @Column(name = "invited_by", length = 100)
    private String invitedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /**
     * Enum for invite status
     */
    public enum InviteStatus {
        PENDING,    // Invite sent, waiting for acceptance
        ACCEPTED,   // User signed up using this invite
        EXPIRED,    // Invite expired
        REVOKED     // Invite was revoked by admin
    }

    /**
     * Default constructor
     */
    public EmailInvite() {
    }

    /**
     * Constructor with required fields
     */
    public EmailInvite(String email, String token, String invitedBy) {
        this.email = email;
        this.token = token;
        this.invitedBy = invitedBy;
        this.status = InviteStatus.PENDING;
    }

    /**
     * Pre-persist callback to set timestamps
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        expiresAt = LocalDateTime.now().plusDays(7); // 7 days expiry
    }

    /**
     * Check if invite is expired
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * Getter for expired property (used by Thymeleaf)
     */
    public boolean getExpired() {
        return isExpired();
    }

    /**
     * Accept the invite
     */
    public void accept() {
        if (status == InviteStatus.PENDING && !isExpired()) {
            this.status = InviteStatus.ACCEPTED;
            this.acceptedAt = LocalDateTime.now();
        }
    }

    /**
     * Revoke the invite
     */
    public void revoke() {
        this.status = InviteStatus.REVOKED;
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public InviteStatus getStatus() {
        return status;
    }

    public void setStatus(InviteStatus status) {
        this.status = status;
    }

    public String getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(String invitedBy) {
        this.invitedBy = invitedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    @Override
    public String toString() {
        return "EmailInvite{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", status=" + status +
                '}';
    }
}
