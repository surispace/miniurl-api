package com.miniurl.identity.controller;

import com.miniurl.identity.service.KeyService;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller to expose the JSON Web Key Set (JWKS) for token validation.
 * This endpoint is used by the API Gateway to fetch the public key for RS256 validation.
 */
@RestController
public class JwksController {

    private final KeyService keyService;

    public JwksController(KeyService keyService) {
        this.keyService = keyService;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public JWKSet getPublicKeys() {
        return keyService.getPublicJWKSet();
    }
}
