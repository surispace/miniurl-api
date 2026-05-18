package com.miniurl.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Paginated response wrapper")
public class PagedResponse<T> {

    @Schema(description = "List of items for the current page")
    private List<T> content;

    @Schema(description = "Current page number (0-indexed)")
    private int page;

    @Schema(description = "Number of items per page")
    private int size;

    @Schema(description = "Total number of items")
    private long totalElements;

    @Schema(description = "Total number of pages")
    private int totalPages;

    @Schema(description = "Whether this is the first page")
    private boolean first;

    @Schema(description = "Whether this is the last page")
    private boolean last;

    @Schema(description = "Sort field")
    private String sortBy;

    @Schema(description = "Sort direction (asc or desc)")
    private String sortDirection;

    public PagedResponse() {}

    public PagedResponse(List<T> content, int page, int size, long totalElements, String sortBy, String sortDirection) {
        this.content = content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.sortBy = sortBy;
        this.sortDirection = sortDirection;
        this.totalPages = (int) Math.ceil((double) totalElements / size);
        this.first = page == 0;
        this.last = page >= totalPages - 1;
    }

    public List<T> getContent() { return content; }
    public void setContent(List<T> content) { this.content = content; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }

    public long getTotalElements() { return totalElements; }
    public void setTotalElements(long totalElements) { this.totalElements = totalElements; }

    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }

    public boolean isFirst() { return first; }
    public void setFirst(boolean first) { this.first = first; }

    public boolean isLast() { return last; }
    public void setLast(boolean last) { this.last = last; }

    public String getSortBy() { return sortBy; }
    public void setSortBy(String sortBy) { this.sortBy = sortBy; }

    public String getSortDirection() { return sortDirection; }
    public void setSortDirection(String sortDirection) { this.sortDirection = sortDirection; }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private List<T> content;
        private int page;
        private int size;
        private long totalElements;
        private String sortBy;
        private String sortDirection;

        public Builder<T> content(List<T> content) { this.content = content; return this; }
        public Builder<T> page(int page) { this.page = page; return this; }
        public Builder<T> size(int size) { this.size = size; return this; }
        public Builder<T> totalElements(long totalElements) { this.totalElements = totalElements; return this; }
        public Builder<T> sortBy(String sortBy) { this.sortBy = sortBy; return this; }
        public Builder<T> sortDirection(String sortDirection) { this.sortDirection = sortDirection; return this; }

        public PagedResponse<T> build() {
            return new PagedResponse<>(content, page, size, totalElements, sortBy, sortDirection);
        }
    }
}
