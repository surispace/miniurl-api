package com.miniurl.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Event sent to the Notification Service via Kafka to trigger an email.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent implements Serializable {
    private String eventType; // e.g., "OTP", "EMAIL_VERIFICATION", "PASSWORD_RESET", "WELCOME", "INVITE", etc.
    private String toEmail;
    private String username;
    private Map<String, Object> payload;
}
