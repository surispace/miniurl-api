package com.miniurl.identity.service;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Service for managing RSA key pairs for RS256 JWT signing.
 * 
 * Keys are persisted to disk so they survive restarts. On first startup,
 * a new key pair is generated and saved. On subsequent restarts, the
 * existing keys are loaded from disk.
 * 
 * For production, generate keys externally and mount as Kubernetes secrets.
 */
@Service
public class KeyService {
    private static final Logger logger = LoggerFactory.getLogger(KeyService.class);

    private final String privateKeyPath;
    private final String publicKeyPath;
    private final String keyId;

    private RSAKey rsaKey;

    public KeyService(
            @Value("${jwt.rsa.private-key-path:config/keys/private.pem}") String privateKeyPath,
            @Value("${jwt.rsa.public-key-path:config/keys/public.pem}") String publicKeyPath,
            @Value("${jwt.rsa.key-id:miniurl-rsa-key-1}") String keyId) {
        this.privateKeyPath = privateKeyPath;
        this.publicKeyPath = publicKeyPath;
        this.keyId = keyId;
    }

    @PostConstruct
    public void init() {
        try {
            Path privPath = Paths.get(privateKeyPath);
            Path pubPath = Paths.get(publicKeyPath);

            if (Files.exists(privPath) && Files.exists(pubPath)) {
                // Load existing keys from disk
                logger.info("Loading existing RSA keys from: {}", privPath.getParent());
                RSAPrivateKey privateKey = loadPrivateKey(privPath);
                RSAPublicKey publicKey = loadPublicKey(pubPath);
                this.rsaKey = new RSAKey.Builder(publicKey)
                        .privateKey(privateKey)
                        .keyID(keyId)
                        .build();
                logger.info("RSA KeyPair loaded successfully with key ID: {}", keyId);
            } else {
                // Generate new keys and persist to disk
                logger.info("No existing RSA keys found. Generating new key pair...");
                Files.createDirectories(privPath.getParent());

                this.rsaKey = new RSAKeyGenerator(2048)
                        .keyID(keyId)
                        .generate();

                // Save keys to disk
                savePrivateKey(privPath, rsaKey.toRSAPrivateKey());
                savePublicKey(pubPath, rsaKey.toRSAPublicKey());

                logger.info("RSA KeyPair generated and persisted successfully with key ID: {}", keyId);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize RSA KeyPair", e);
            throw new RuntimeException("Critical failure: Could not initialize security keys", e);
        }
    }

    public PrivateKey getPrivateKey() {
        try {
            return rsaKey.toPrivateKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get private key", e);
        }
    }

    public PublicKey getPublicKey() {
        try {
            return rsaKey.toPublicKey();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get public key", e);
        }
    }

    /**
     * Returns the public key as a JWKSet for the JWKS endpoint.
     * Always returns the same stored key — NOT a freshly generated one.
     */
    public JWKSet getPublicJWKSet() {
        try {
            RSAKey publicJWK = rsaKey.toPublicJWK();
            return new JWKSet(publicJWK);
        } catch (Exception e) {
            logger.error("Failed to generate JWK set", e);
            throw new RuntimeException("Failed to generate JWK set", e);
        }
    }

    // --- Private key persistence helpers ---

    private RSAPrivateKey loadPrivateKey(Path path) throws Exception {
        String content = Files.readString(path);
        String key = content
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(key);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) keyFactory.generatePrivate(spec);
    }

    private RSAPublicKey loadPublicKey(Path path) throws Exception {
        String content = Files.readString(path);
        String key = content
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(key);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) keyFactory.generatePublic(spec);
    }

    private void savePrivateKey(Path path, RSAPrivateKey key) throws IOException {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + encoded + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(path, pem);
        logger.debug("Private key saved to: {}", path);
    }

    private void savePublicKey(Path path, RSAPublicKey key) throws IOException {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(key.getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(path, pem);
        logger.debug("Public key saved to: {}", path);
    }
}
