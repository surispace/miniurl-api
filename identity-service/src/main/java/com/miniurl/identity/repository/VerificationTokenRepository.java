package com.miniurl.identity.repository;

import com.miniurl.identity.entity.VerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VerificationTokenRepository extends JpaRepository<VerificationToken, Long> {
    Optional<VerificationToken> findByToken(String token);
    Optional<VerificationToken> findByTokenAndTokenType(String token, String tokenType);
    Optional<VerificationToken> findByUserIdAndTokenTypeAndUsedFalse(Long userId, String tokenType);
    List<VerificationToken> findByUserIdAndTokenType(Long userId, String tokenType);
    List<VerificationToken> findByUserId(Long userId);
    List<VerificationToken> findByTokenType(String tokenType);
    void deleteByUserId(Long userId);
    void deleteByToken(String token);
}
