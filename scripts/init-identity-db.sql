-- MySQL Database Initialization Script for Identity Service
CREATE DATABASE IF NOT EXISTS identity_db;
USE identity_db;

-- ===========================================
-- ROLES TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO roles (id, name, description) VALUES
    (1, 'ADMIN', 'Administrator with full access'),
    (2, 'USER', 'Regular user with limited access')
ON DUPLICATE KEY UPDATE name=name;

-- ===========================================
-- USERS TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    username VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role_id BIGINT DEFAULT 2,
    must_change_password BOOLEAN DEFAULT FALSE,
    last_login DATETIME,
    status ENUM('ACTIVE','DELETED','SUSPENDED') NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INT DEFAULT 0,
    lockout_time DATETIME,
    theme ENUM('LIGHT', 'DARK', 'OCEAN', 'FOREST') DEFAULT 'LIGHT',
    token_version INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE SET NULL,
    INDEX idx_email (email),
    INDEX idx_username (username),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================
-- VERIFICATION_TOKENS TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS verification_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    token_type VARCHAR(50) NOT NULL,
    expiry_time DATETIME NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_token (token),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================
-- EMAIL_INVITES TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS email_invites (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL,
    token VARCHAR(255) NOT NULL UNIQUE,
    status ENUM('PENDING','ACCEPTED','EXPIRED','REVOKED') NOT NULL DEFAULT 'PENDING',
    invited_by VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME,
    accepted_at DATETIME,
    INDEX idx_email (email),
    INDEX idx_token (token),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================
-- AUDIT_LOGS TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id BIGINT,
    details TEXT,
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Default Admin User
INSERT INTO users (email, first_name, last_name, username, password, role_id, must_change_password, status, failed_login_attempts, token_version)
SELECT 'admin@miniurl.com', 'Admin', 'User', 'admin',
       '$2a$10$8.UnS6S.S6S.S6S.S6S.S6S.S6S.S6S.S6S.S6S.S6S.S6S.S6S', -- Placeholder hash
       1, true, 'ACTIVE', 0, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = 'admin'
);
