package com.miniurl.redirect.controller;

import com.miniurl.common.dto.ClickEvent;
import com.miniurl.redirect.service.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.LocalDateTime;

@Slf4j
@RestController
@Tag(name = "Redirect", description = "High-throughput URL redirect endpoint")
public class RedirectController {

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    @Operation(summary = "Redirect by short code", description = "Resolves a short code and redirects to the original URL. Publishes click event asynchronously.")
    @ApiResponses({
        @ApiResponse(responseCode = "302", description = "Redirect to original URL"),
        @ApiResponse(responseCode = "404", description = "Short URL not found"),
        @ApiResponse(responseCode = "400", description = "Blocked redirect (malicious URL)")
    })
    @GetMapping("/r/{code}")
    public Mono<ResponseEntity<Object>> redirect(@PathVariable String code,
                                                  @RequestHeader(value = "User-Agent", required = false) String userAgent,
                                                  @RequestHeader(value = "Referer", required = false) String referer,
                                                  @RequestHeader(value = "X-Forwarded-For", required = false) String xForwardedFor,
                                                  ServerWebExchange exchange) {
        
        Long userId = exchange.getAttribute("userId");
        
        return redirectService.resolveUrl(code)
            .flatMap(originalUrl -> {
                // Validate redirect URL to prevent open redirect attacks
                if (!isValidRedirectUrl(originalUrl)) {
                    log.warn("Blocked redirect to potentially malicious URL: {}", originalUrl);
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
                }

                // Async click event publishing
                ClickEvent event = ClickEvent.builder()
                    .shortCode(code)
                    .originalUrl(originalUrl)
                    .ipAddress(xForwardedFor != null ? xForwardedFor.split(",")[0] : "unknown")
                    .userAgent(userAgent)
                    .referer(referer)
                    .timestamp(LocalDateTime.now())
                    .userId(userId)
                    .build();
                
                return redirectService.publishClickEvent(event)
                    .thenReturn(ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(originalUrl))
                        .build());
            })
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Short URL not found")));
    }

    /**
     * Validate URL before redirect to prevent open redirect attacks.
     * Only allows http: and https: protocols. Blocks javascript:, data:,
     * vbscript:, file:, and any other non-http schemes.
     * Ported from monolith RedirectController.isValidRedirectUrl().
     */
    private boolean isValidRedirectUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URL parsedUrl = new URL(url);
            String protocol = parsedUrl.getProtocol();

            // Only allow http and https
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                return false;
            }

            // Block dangerous protocols that could have bypassed creation validation
            String lowerUrl = url.toLowerCase().trim();
            if (lowerUrl.startsWith("javascript:") ||
                lowerUrl.startsWith("data:") ||
                lowerUrl.startsWith("vbscript:") ||
                lowerUrl.startsWith("file:")) {
                return false;
            }

            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }
}
