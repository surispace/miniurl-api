package com.miniurl.identity.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * CAPTCHA verification service with pluggable provider support.
 *
 * Implements OWASP ASVS V2.1 — bot detection on authentication endpoints.
 * Supports three modes:
 *   - none: CAPTCHA verification is disabled (default for dev)
 *   - recaptcha-v3: Google reCAPTCHA v3 (score-based, invisible)
 *   - hcaptcha: hCaptcha (challenge-based)
 *
 * In production, set captcha.provider=recaptcha-v3 and provide a secret key.
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    private final String provider;
    private final String secretKey;
    private final double scoreThreshold;
    private final boolean enabled;
    private final RestTemplate restTemplate;

    public CaptchaService(
            @Value("${app.captcha.provider:none}") String provider,
            @Value("${app.captcha.secret-key:}") String secretKey,
            @Value("${app.captcha.score-threshold:0.5}") double scoreThreshold,
            RestTemplate restTemplate) {
        this.provider = provider;
        this.secretKey = secretKey;
        this.scoreThreshold = scoreThreshold;
        this.enabled = !"none".equalsIgnoreCase(provider);
        this.restTemplate = restTemplate;

        if (enabled && (secretKey == null || secretKey.isBlank())) {
            log.warn("CAPTCHA provider '{}' is enabled but no secret key is configured. " +
                    "CAPTCHA verification will fail for all requests.", provider);
        }

        log.info("CAPTCHA service initialized: provider={}, enabled={}", provider, enabled);
    }

    /**
     * Returns whether CAPTCHA verification is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Validates a CAPTCHA token.
     *
     * @param captchaToken the token from the client-side CAPTCHA widget
     * @param remoteIp     the client's IP address (optional, for provider verification)
     * @throws CaptchaValidationException if the token is invalid or verification fails
     */
    public void verifyCaptcha(String captchaToken, String remoteIp) {
        if (!enabled) {
            return; // CAPTCHA disabled — allow all requests
        }

        if (captchaToken == null || captchaToken.isBlank()) {
            throw new CaptchaValidationException("CAPTCHA token is required");
        }

        switch (provider.toLowerCase()) {
            case "recaptcha-v3":
                verifyRecaptchaV3(captchaToken, remoteIp);
                break;
            case "hcaptcha":
                verifyHCaptcha(captchaToken, remoteIp);
                break;
            default:
                log.warn("Unknown CAPTCHA provider '{}' — allowing request", provider);
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyRecaptchaV3(String token, String remoteIp) {
        String url = "https://www.google.com/recaptcha/api/siteverify"
                + "?secret=" + secretKey
                + "&response=" + token
                + (remoteIp != null ? "&remoteip=" + remoteIp : "");

        try {
            Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
            if (response == null) {
                throw new CaptchaValidationException("Empty response from reCAPTCHA");
            }

            Boolean success = (Boolean) response.get("success");
            if (!Boolean.TRUE.equals(success)) {
                log.warn("reCAPTCHA verification failed: {}", response.get("error-codes"));
                throw new CaptchaValidationException("CAPTCHA verification failed");
            }

            Double score = (Double) response.get("score");
            if (score != null && score < scoreThreshold) {
                log.warn("reCAPTCHA score too low: {} < {}", score, scoreThreshold);
                throw new CaptchaValidationException("CAPTCHA verification failed — suspicious activity detected");
            }

            log.debug("reCAPTCHA v3 verified: score={}", score);
        } catch (CaptchaValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("reCAPTCHA verification error", e);
            throw new CaptchaValidationException("CAPTCHA verification service unavailable");
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyHCaptcha(String token, String remoteIp) {
        String url = "https://hcaptcha.com/siteverify";
        String body = "secret=" + secretKey
                + "&response=" + token
                + (remoteIp != null ? "&remoteip=" + remoteIp : "");

        try {
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response == null) {
                throw new CaptchaValidationException("Empty response from hCaptcha");
            }

            Boolean success = (Boolean) response.get("success");
            if (!Boolean.TRUE.equals(success)) {
                log.warn("hCaptcha verification failed: {}", response.get("error-codes"));
                throw new CaptchaValidationException("CAPTCHA verification failed");
            }

            log.debug("hCaptcha verified successfully");
        } catch (CaptchaValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("hCaptcha verification error", e);
            throw new CaptchaValidationException("CAPTCHA verification service unavailable");
        }
    }

    /**
     * Exception thrown when CAPTCHA verification fails.
     */
    public static class CaptchaValidationException extends RuntimeException {
        public CaptchaValidationException(String message) {
            super(message);
        }
    }
}
