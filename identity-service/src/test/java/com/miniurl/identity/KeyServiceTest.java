package com.miniurl.identity;

import com.miniurl.identity.service.KeyService;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("KeyService Tests")
class KeyServiceTest {

    private KeyService keyService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        String privPath = tempDir.resolve("private.pem").toString();
        String pubPath = tempDir.resolve("public.pem").toString();
        keyService = new KeyService(privPath, pubPath, "test-key-id");
        keyService.init();
    }

    @Test
    @DisplayName("init generates RSA key pair")
    void initGeneratesKeys() {
        assertNotNull(keyService.getPrivateKey());
        assertNotNull(keyService.getPublicKey());
    }

    @Test
    @DisplayName("getPublicJWKSet returns consistent key across calls")
    void getPublicJWKSetReturnsConsistentKey() {
        JWKSet firstCall = keyService.getPublicJWKSet();
        JWKSet secondCall = keyService.getPublicJWKSet();
        assertEquals(
            firstCall.getKeys().get(0).getKeyID(),
            secondCall.getKeys().get(0).getKeyID(),
            "JWK set should return the same key ID on every call"
        );
        assertEquals(
            firstCall.toJSONObject(false).toString(),
            secondCall.toJSONObject(false).toString(),
            "JWK set JSON representation should be identical across calls"
        );
    }

    @Test
    @DisplayName("getPrivateKey returns a usable PrivateKey")
    void getPrivateKeyReturnsUsableKey() {
        PrivateKey privateKey = keyService.getPrivateKey();
        assertNotNull(privateKey);
        assertEquals("RSA", privateKey.getAlgorithm());
    }

    @Test
    @DisplayName("getPublicKey returns a usable PublicKey")
    void getPublicKeyReturnsUsableKey() {
        PublicKey publicKey = keyService.getPublicKey();
        assertNotNull(publicKey);
        assertEquals("RSA", publicKey.getAlgorithm());
    }

    @Test
    @DisplayName("keys persist across restarts (re-init loads from disk)")
    void keysPersistAcrossRestarts(@TempDir Path tempDir) {
        String privPath = tempDir.resolve("persist-private.pem").toString();
        String pubPath = tempDir.resolve("persist-public.pem").toString();

        // First "startup" — generates and saves keys
        KeyService firstInstance = new KeyService(privPath, pubPath, "persist-key");
        firstInstance.init();
        PublicKey firstPublicKey = firstInstance.getPublicKey();

        // Second "startup" — should load the same keys from disk
        KeyService secondInstance = new KeyService(privPath, pubPath, "persist-key");
        secondInstance.init();
        PublicKey secondPublicKey = secondInstance.getPublicKey();

        assertEquals(firstPublicKey, secondPublicKey,
                "Keys should be identical across restarts");
    }
}
