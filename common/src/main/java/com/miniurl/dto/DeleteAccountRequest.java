package com.miniurl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class DeleteAccountRequest {

    @NotNull(message = "User ID is required")
    private Long userId;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    public DeleteAccountRequest() {}

    public DeleteAccountRequest(Long userId, String password) {
        this.userId = userId;
        this.password = password;
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
