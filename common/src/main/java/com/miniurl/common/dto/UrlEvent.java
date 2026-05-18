package com.miniurl.common.dto;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlEvent {
    private Long urlId;
    private String shortCode;
    private String originalUrl;
    private Long userId;
    private String eventType; // e.g., "CREATED", "DELETED"
}
