package com.miniurl.identity.service;

import com.miniurl.identity.entity.User;
import com.miniurl.identity.entity.VerificationToken;
import com.miniurl.identity.repository.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

@Service
public class TokenService {

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final VerificationTokenRepository tokenRepository;

    @Value("${app.token.expiry-minutes:15}")
    private int tokenExpiryMinutes;

    public TokenService(VerificationTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    /**
     * Generate a random verification token
     */
    public String generateToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Generate a numeric OTP
     */
    public String generateOtp() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(secureRandom.nextInt(10));
        }
        return sb.toString();
    }

    /**
     * Create email verification token for user
     */
    @Transactional
    public VerificationToken createEmailVerificationToken(User user) {
        // Invalidate any existing email verification tokens for this user
        invalidateUserTokens(user.getId(), VerificationToken.TYPE_EMAIL_VERIFICATION);

        String token = generateToken();
        LocalDateTime expiryTime = LocalDateTime.now().plusMinutes(tokenExpiryMinutes);

        VerificationToken verificationToken = VerificationToken.builder()
            .user(user)
            .token(token)
            .tokenType(VerificationToken.TYPE_EMAIL_VERIFICATION)
            .expiryTime(expiryTime)
            .build();

        return tokenRepository.save(verificationToken);
    }

    /**
     * Create password reset token for user
     */
    @Transactional
    public VerificationToken createPasswordResetToken(User user) {
        // Invalidate any existing password reset tokens for this user
        invalidateUserTokens(user.getId(), VerificationToken.TYPE_PASSWORD_RESET);

        String token = generateToken();
        LocalDateTime expiryTime = LocalDateTime.now().plusMinutes(tokenExpiryMinutes);

        VerificationToken verificationToken = VerificationToken.builder()
            .user(user)
            .token(token)
            .tokenType(VerificationToken.TYPE_PASSWORD_RESET)
            .expiryTime(expiryTime)
            .build();

        return tokenRepository.save(verificationToken);
    }

    /**
     * Validate a token (generic, works for any token type)
     * Does NOT mark token as used - caller is responsible for that.
     */
    @Transactional(readOnly = true)
    public Optional<VerificationToken> validateToken(String token) {
        Optional<VerificationToken> tokenOpt = tokenRepository.findByToken(token);

        if (tokenOpt.isEmpty()) {
            return Optional.empty();
        }

        VerificationToken verificationToken = tokenOpt.get();

        if (!verificationToken.isValid()) {
            return Optional.empty();
        }

        return Optional.of(verificationToken);
    }

    /**
     * Invalidate all tokens of a specific type for a user
     */
    @Transactional
    public void invalidateUserTokens(Long userId, String tokenType) {
        tokenRepository.findByUserIdAndTokenTypeAndUsedFalse(userId, tokenType)
            .ifPresent(token -> {
                token.setUsed(true);
                tokenRepository.save(token);
            });
    }

    /**
     * Invalidate ALL tokens (used and unused) of a specific type for a user
     * Used when reactivating soft-deleted users
     */
    @Transactional
    public void invalidateAllUserTokens(Long userId, String tokenType) {
        tokenRepository.findByUserIdAndTokenType(userId, tokenType)
            .forEach(token -> {
                token.setUsed(true);
                tokenRepository.save(token);
            });
    }

    /**
     * Clean up expired tokens
     */
    @Transactional
    public void cleanupExpiredTokens() {
        tokenRepository.findAll().stream()
            .filter(VerificationToken::isExpired)
            .forEach(token -> tokenRepository.delete(token));
    }
}
