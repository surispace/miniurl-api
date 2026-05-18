package com.miniurl.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@Schema(description = "Pagination request parameters")
public class PageableRequest {

    @Min(value = 0, message = "Page number must be 0 or greater")
    @Schema(description = "Page number (0-indexed, min: 0)", example = "0", defaultValue = "0")
    private int page = 0;

    @Min(value = 1, message = "Page size must be at least 1")
    @Max(value = 100, message = "Page size must be 100 or less")
    @Schema(description = "Items per page (min: 1, max: 100)", example = "10", defaultValue = "10")
    private int size = 10;

    @Schema(description = "Sort field (allowed: id, originalUrl, shortCode, accessCount, createdAt)", example = "createdAt", defaultValue = "createdAt")
    private String sortBy = "createdAt";

    @Pattern(regexp = "^(?i)(asc|desc)$", message = "Sort direction must be 'asc' or 'desc'")
    @Schema(description = "Sort direction (allowed: asc, desc)", example = "desc", defaultValue = "desc")
    private String sortDirection = "desc";

    public PageableRequest() {}

    public PageableRequest(int page, int size, String sortBy, String sortDirection) {
        this.page = page;
        this.size = size;
        this.sortBy = sortBy;
        this.sortDirection = sortDirection;
    }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public String getSortBy() { return sortBy; }
    public void setSortBy(String sortBy) { this.sortBy = sortBy; }

    public String getSortDirection() { return sortDirection; }
    public void setSortDirection(String sortDirection) { this.sortDirection = sortDirection; }

    public boolean isAscending() {
        return "asc".equalsIgnoreCase(sortDirection);
    }
}
