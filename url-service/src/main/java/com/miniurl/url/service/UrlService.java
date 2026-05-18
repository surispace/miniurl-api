package com.miniurl.url.service;

import com.miniurl.dto.CreateUrlRequest;
import com.miniurl.dto.PagedResponse;
import com.miniurl.dto.PageableRequest;
import com.miniurl.dto.UrlResponse;
import com.miniurl.exception.AliasNotAvailableException;
import com.miniurl.exception.ResourceNotFoundException;
import com.miniurl.exception.UrlValidationException;
import com.miniurl.url.client.RedirectServiceClient;
import com.miniurl.url.entity.Url;
import com.miniurl.url.repository.UrlRepository;
import com.miniurl.url.util.Base62;
import com.miniurl.url.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    private static final Set<String> BLOCKED_DOMAINS = Set.of(
        "localhost", "127.0.0.1", "0.0.0.0", "::1",
        "metadata.google.internal", "169.254.169.254",
        "instance-data", "metadata.azure.com", "metadata.digitalocean.com"
    );

    private final UrlRepository urlRepository;
    private final UrlUsageLimitService urlUsageLimitService;
    private final SnowflakeIdGenerator idGenerator;
    private final OutboxService outboxService;
    private final RedirectServiceClient redirectServiceClient;

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.ui-base-url:http://localhost:3000}")
    private String uiBaseUrl;

    public UrlService(UrlRepository urlRepository,
                      UrlUsageLimitService urlUsageLimitService,
                      SnowflakeIdGenerator idGenerator,
                      OutboxService outboxService,
                      RedirectServiceClient redirectServiceClient) {
        this.urlRepository = urlRepository;
        this.urlUsageLimitService = urlUsageLimitService;
        this.idGenerator = idGenerator;
        this.outboxService = outboxService;
        this.redirectServiceClient = redirectServiceClient;
    }

    private String generateUniqueShortCode() {
        return Base62.encode(idGenerator.nextId());
    }

    private void validateUrl(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            throw new UrlValidationException("URL cannot be empty");
        }

        String lowerUrl = urlString.toLowerCase().trim();
        if (lowerUrl.startsWith("javascript:") ||
            lowerUrl.startsWith("data:") ||
            lowerUrl.startsWith("vbscript:") ||
            lowerUrl.startsWith("file:") ||
            lowerUrl.startsWith("ftp:") ||
            lowerUrl.startsWith("about:")) {
            throw new UrlValidationException("URL protocol not allowed");
        }

        try {
            URL url = new URL(urlString);
            String protocol = url.getProtocol();
            
            if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
                throw new UrlValidationException("URL must use http or https protocol");
            }

            String host = url.getHost();
            if (host == null || host.isEmpty()) {
                throw new UrlValidationException("Invalid URL: missing host");
            }

            if (isSelfReferencingUrl(host)) {
                throw new UrlValidationException("Shortening URLs for this domain is not allowed");
            }

            if (BLOCKED_DOMAINS.contains(host.toLowerCase())) {
                throw new UrlValidationException("URL host is not allowed");
            }

            if (isPrivateOrInternalIp(host)) {
                throw new UrlValidationException("Internal/private IP addresses are not allowed");
            }

            if (host.endsWith(".internal") ||
                host.equals("metadata.google.internal") ||
                host.equals("169.254.169.254") ||
                host.contains("metadata")) {
                throw new UrlValidationException("Cloud metadata endpoints are not allowed");
            }

        } catch (MalformedURLException e) {
            throw new UrlValidationException("Invalid URL format");
        }
    }

    private boolean isSelfReferencingUrl(String host) {
        String normalizedHost = normalizeHost(host);
        String normalizedBaseUrl = normalizeHost(extractHost(baseUrl));
        String normalizedUiUrl = normalizeHost(extractHost(uiBaseUrl));

        return (!normalizedBaseUrl.isEmpty() && normalizedHost.equals(normalizedBaseUrl)) ||
               (!normalizedUiUrl.isEmpty() && normalizedHost.equals(normalizedUiUrl));
    }

    private String extractHost(String urlString) {
        try {
            URL url = new URL(urlString);
            return url.getHost();
        } catch (MalformedURLException e) {
            return "";
        }
    }

    private String normalizeHost(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.toLowerCase().trim();
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    private boolean isPrivateOrInternalIp(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (addr.isLoopbackAddress() ||
                    addr.isAnyLocalAddress() ||
                    addr.isLinkLocalAddress() ||
                    addr.isSiteLocalAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private void validateAlias(String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            return;
        }

        if (alias.length() < 3) {
            throw new UrlValidationException("Alias must be at least 3 characters");
        }

        if (alias.length() > 10) {
            throw new UrlValidationException("Alias must be 10 characters or less");
        }

        if (!alias.matches("^[a-zA-Z0-9]+$")) {
            throw new UrlValidationException("Alias must contain only alphanumeric characters (letters and numbers)");
        }
    }

    @Transactional
    public UrlResponse createUrl(CreateUrlRequest request, Long userId) {
        validateUrl(request.getUrl());
        validateAlias(request.getAlias());

        urlUsageLimitService.checkAndIncrementUrlCreation(userId);

        String shortCode;
        if (request.getAlias() != null && !request.getAlias().trim().isEmpty()) {
            shortCode = request.getAlias().trim();
            if (urlRepository.existsByShortCode(shortCode)) {
                throw new AliasNotAvailableException("Alias not available");
            }
        } else {
            shortCode = generateUniqueShortCode();
        }

        Url url = Url.builder()
            .originalUrl(request.getUrl())
            .shortCode(shortCode)
            .userId(userId)
            .build();

        Url savedUrl = urlRepository.save(url);

        com.miniurl.common.dto.UrlEvent event = com.miniurl.common.dto.UrlEvent.builder()
            .urlId(savedUrl.getId())
            .shortCode(savedUrl.getShortCode())
            .originalUrl(savedUrl.getOriginalUrl())
            .userId(userId)
            .eventType("CREATED")
            .build();
        
        outboxService.saveEvent("URL", String.valueOf(savedUrl.getId()), "URL_CREATED", event);

        return convertToResponse(savedUrl);
    }

    public List<UrlResponse> getUserUrls(Long userId) {
        List<Url> urls = urlRepository.findByUserId(userId);
        return urls.stream()
            .map(this::convertToResponse)
            .collect(Collectors.toList());
    }

    public PagedResponse<UrlResponse> getUserUrls(Long userId, PageableRequest pageableRequest) {
        String sortBy = validateSortField(pageableRequest.getSortBy());
        
        Sort sort = pageableRequest.isAscending() ? 
            Sort.by(sortBy).ascending() : 
            Sort.by(sortBy).descending();
        
        PageRequest pageRequest = PageRequest.of(
            pageableRequest.getPage(), 
            pageableRequest.getSize(), 
            sort
        );

        Page<Url> urlPage = urlRepository.findByUserId(userId, pageRequest);
        List<UrlResponse> content = urlPage.getContent().stream()
            .map(this::convertToResponse)
            .collect(Collectors.toList());

        return PagedResponse.<UrlResponse>builder()
            .content(content)
            .page(pageableRequest.getPage())
            .size(pageableRequest.getSize())
            .totalElements(urlPage.getTotalElements())
            .sortBy(sortBy)
            .sortDirection(pageableRequest.getSortDirection())
            .build();
    }

    private String validateSortField(String sortBy) {
        if (sortBy == null || sortBy.trim().isEmpty()) {
            return "createdAt";
        }
        
        Set<String> allowedFields = Set.of("id", "originalUrl", "shortCode", "accessCount", "createdAt");
        String field = sortBy.trim();
        
        if (!allowedFields.contains(field)) {
            return "createdAt";
        }
        
        return field;
    }

    @Transactional
    public void deleteUrl(Long urlId, Long userId) {
        Url url = urlRepository.findByIdAndUserId(urlId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("URL not found"));

        com.miniurl.common.dto.UrlEvent event = com.miniurl.common.dto.UrlEvent.builder()
            .urlId(url.getId())
            .shortCode(url.getShortCode())
            .originalUrl(url.getOriginalUrl())
            .userId(userId)
            .eventType("DELETED")
            .build();
        
        outboxService.saveEvent("URL", String.valueOf(url.getId()), "URL_DELETED", event);
        
        urlRepository.delete(url);

        try {
            redirectServiceClient.invalidateCache(url.getShortCode());
            log.debug("Cache invalidated for deleted URL: {}", url.getShortCode());
        } catch (Exception e) {
            log.warn("Failed to invalidate redirect cache for {}: {}", url.getShortCode(), e.getMessage());
        }
    }

    public UrlResponse getUrlById(Long urlId, Long userId) {
        Url url = urlRepository.findByIdAndUserId(urlId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("URL not found"));

        return convertToResponse(url);
    }

    @Transactional
    public Optional<Url> findByShortCode(String shortCode) {
        Optional<Url> url = urlRepository.findByShortCode(shortCode);
        url.ifPresent(u -> {
            u.setAccessCount(u.getAccessCount() + 1);
            urlRepository.save(u);
        });
        return url;
    }

    public Optional<String> resolveShortCode(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
            .map(Url::getOriginalUrl);
    }

    private UrlResponse convertToResponse(Url url) {
        String shortUrl = baseUrl + "/r/" + url.getShortCode();
        return UrlResponse.builder()
            .id(url.getId())
            .originalUrl(url.getOriginalUrl())
            .shortCode(url.getShortCode())
            .shortUrl(shortUrl)
            .accessCount(url.getAccessCount())
            .createdAt(url.getCreatedAt())
            .build();
    }

    public UrlUsageLimitService.UrlUsageStats getUsageStats(Long userId) {
        return urlUsageLimitService.getUsageStats(userId);
    }
}
