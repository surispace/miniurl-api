package com.miniurl.url.controller;

import com.miniurl.dto.ApiResponse;
import com.miniurl.dto.CreateUrlRequest;
import com.miniurl.dto.PagedResponse;
import com.miniurl.dto.PageableRequest;
import com.miniurl.dto.UrlResponse;
import com.miniurl.url.service.UrlService;
import com.miniurl.url.service.UrlUsageLimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/urls")
@Tag(name = "URL Management", description = "URL shortening, retrieval, and management endpoints")
public class UrlController {

    private final UrlService urlService;

    public UrlController(UrlService urlService) {
        this.urlService = urlService;
    }

    @Operation(summary = "Create a short URL", description = "Shortens a given URL for the authenticated user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL created successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid request body"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping
    public ResponseEntity<ApiResponse> createUrl(
            @Valid @RequestBody CreateUrlRequest request,
            @RequestAttribute("userId") Long userId) {
        UrlResponse response = urlService.createUrl(request, userId);
        return ResponseEntity.ok(ApiResponse.success("URL created successfully", response));
    }

    @Operation(summary = "List user URLs", description = "Returns all URLs belonging to the authenticated user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User URLs retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping
    public ResponseEntity<ApiResponse> getUserUrls(
            @RequestAttribute("userId") Long userId) {
        List<UrlResponse> urls = urlService.getUserUrls(userId);
        return ResponseEntity.ok(ApiResponse.success("User URLs retrieved successfully", urls));
    }

    @Operation(summary = "List user URLs (paged)", description = "Returns a paginated list of URLs belonging to the authenticated user.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User URLs retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/paged")
    public ResponseEntity<ApiResponse> getUserUrlsPaged(
            @RequestBody PageableRequest pageableRequest,
            @RequestAttribute("userId") Long userId) {
        PagedResponse<UrlResponse> response = urlService.getUserUrls(userId, pageableRequest);
        return ResponseEntity.ok(ApiResponse.success("User URLs retrieved successfully", response));
    }

    @Operation(summary = "Get URL by ID", description = "Returns a single URL by its ID. Only accessible by the URL owner.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "URL not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse> getUrlById(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        UrlResponse response = urlService.getUrlById(id, userId);
        return ResponseEntity.ok(ApiResponse.success("URL retrieved successfully", response));
    }

    @Operation(summary = "Delete URL", description = "Deletes a URL by its ID. Only accessible by the URL owner.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL deleted successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "URL not found")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse> deleteUrl(
            @PathVariable Long id,
            @RequestAttribute("userId") Long userId) {
        urlService.deleteUrl(id, userId);
        return ResponseEntity.ok(ApiResponse.success("URL deleted successfully", null));
    }

    @Operation(summary = "Get URL usage stats", description = "Returns usage statistics for the authenticated user's URLs.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "URL usage stats retrieved successfully"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @GetMapping("/usage-stats")
    public ResponseEntity<ApiResponse> getUsageStats(
            @RequestAttribute("userId") Long userId) {
        UrlUsageLimitService.UrlUsageStats stats = urlService.getUsageStats(userId);
        return ResponseEntity.ok(ApiResponse.success("URL usage stats retrieved successfully", stats));
    }
}
