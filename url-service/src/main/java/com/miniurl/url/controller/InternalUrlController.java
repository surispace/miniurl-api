package com.miniurl.url.controller;

import com.miniurl.dto.UrlResponse;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.miniurl.url.service.UrlService;

import java.util.List;
import java.util.Optional;

@Hidden
@RestController
@RequestMapping("/internal/urls")
@RequiredArgsConstructor
public class InternalUrlController {

    private final UrlService urlService;

    @GetMapping("/resolve/{code}")
    public ResponseEntity<String> resolve(@PathVariable String code) {
        return urlService.resolveShortCode(code)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Internal endpoint for fetching all URLs belonging to a user.
     * Used by identity-service for data export.
     */
    @GetMapping("/by-user/{userId}")
    public ResponseEntity<List<UrlResponse>> getUrlsByUserId(@PathVariable Long userId) {
        List<UrlResponse> urls = urlService.getUserUrls(userId);
        return ResponseEntity.ok(urls);
    }
}
