package com.miniurl.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {
    private String shortCode;
    private String originalUrl;
    private String ipAddress;
    private String userAgent;
    private String referer;
    private LocalDateTime timestamp;
    private Long userId; // Optional, if authenticated
}
