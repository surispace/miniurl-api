package com.miniurl.identity.entity;

public enum UserStatus {
    ACTIVE("Active"),
    DELETED("Deleted"),
    SUSPENDED("Suspended");

    private final String displayName;

    UserStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
