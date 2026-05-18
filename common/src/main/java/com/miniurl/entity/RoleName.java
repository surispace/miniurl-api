package com.miniurl.entity;

public enum RoleName {
    ADMIN("Administrator with full access"),
    USER("Regular user with limited access");

    private final String description;

    RoleName(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
