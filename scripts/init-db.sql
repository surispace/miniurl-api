-- MySQL Database Initialization Script for MyURL
-- This script creates the database, tables, and initializes all required data
-- SAFE TO RUN MULTIPLE TIMES - All operations use IF NOT EXISTS or ON DUPLICATE KEY UPDATE

-- ===========================================
-- DATABASE
-- ===========================================
CREATE DATABASE IF NOT EXISTS miniurldb;

-- Use the database
USE miniurldb;

-- ===========================================
-- ROLES TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS roles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert default roles (idempotent)
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
-- URLS TABLE
-- ===========================================
CREATE TABLE IF NOT EXISTS urls (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_url VARCHAR(2048) NOT NULL,
    short_code VARCHAR(10) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    access_count BIGINT DEFAULT 0,
    CONSTRAINT fk_urls_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_short_code (short_code),
    INDEX idx_user_id (user_id)
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
    CONSTRAINT fk_verification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
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
-- URL_USAGE_LIMITS TABLE (Per-user rate limiting)
-- ===========================================
CREATE TABLE IF NOT EXISTS url_usage_limits (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    period_year INT NOT NULL,
    period_month INT NOT NULL,
    daily_count INT NOT NULL DEFAULT 0,
    monthly_count INT NOT NULL DEFAULT 0,
    last_reset_date DATETIME,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_period (user_id, period_year, period_month),
    INDEX idx_user_id (user_id),
    INDEX idx_period (period_year, period_month),
    CONSTRAINT fk_usage_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
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
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_user_id (user_id),
    INDEX idx_action (action),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================
-- FEATURES TABLE (Master feature definitions)
-- ===========================================
CREATE TABLE IF NOT EXISTS features (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_key VARCHAR(100) NOT NULL UNIQUE,
    feature_name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_feature_key (feature_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert 10 master features (idempotent)
INSERT INTO features (feature_key, feature_name, description) VALUES
    ('GLOBAL_USER_SIGNUP', 'User Sign Up', 'Allow new user registration (global)'),
    ('GLOBAL_APP_NAME', 'MyURL', 'A place for all your URL''s!'),
    ('PROFILE_PAGE', 'Profile', 'User profile management'),
    ('EXPORT_JSON', 'Export to JSON', 'Export user data as JSON'),
    ('URL_SHORTENING', 'URL Shortening', 'Create short URLs'),
    ('DASHBOARD', 'Dashboard', 'User dashboard'),
    ('SETTINGS_PAGE', 'Settings', 'Account settings and password change'),
    ('EMAIL_INVITE', 'Email Invitations', 'Send email invitations for user signup'),
    ('USER_MANAGEMENT', 'User Management', 'Admin user management'),
    ('FEATURE_MANAGEMENT', 'Feature Management', 'Manage application features'),
    ('TWO_FACTOR_AUTH', 'Two-Factor Authentication', 'Require OTP verification after login')
ON DUPLICATE KEY UPDATE feature_name = VALUES(feature_name);

-- ===========================================
-- FEATURE_FLAGS TABLE (Role-based enabled status)
-- ===========================================
CREATE TABLE IF NOT EXISTS feature_flags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_feature_flags_feature FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE,
    CONSTRAINT fk_feature_flags_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    UNIQUE KEY uk_feature_role (feature_id, role_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert feature flags for USER role (role_id=2)
-- EMAIL_INVITE, USER_MANAGEMENT, FEATURE_MANAGEMENT disabled for USER role
INSERT INTO feature_flags (feature_id, role_id, enabled)
SELECT f.id, 2, CASE WHEN f.feature_key IN ('EMAIL_INVITE', 'USER_MANAGEMENT', 'FEATURE_MANAGEMENT') THEN false ELSE true END
FROM features f
WHERE f.feature_key IN (
    'PROFILE_PAGE', 'EXPORT_JSON', 'URL_SHORTENING',
    'DASHBOARD', 'SETTINGS_PAGE', 'EMAIL_INVITE',
    'USER_MANAGEMENT', 'FEATURE_MANAGEMENT'
)
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled);

-- Insert feature flags for ADMIN role (role_id=1)
-- All 8 features enabled (excluding GLOBAL_USER_SIGNUP which is global)
INSERT INTO feature_flags (feature_id, role_id, enabled)
SELECT f.id, 1, true 
FROM features f 
WHERE f.feature_key IN (
    'PROFILE_PAGE', 'EXPORT_JSON', 'URL_SHORTENING', 
    'DASHBOARD', 'SETTINGS_PAGE', 'EMAIL_INVITE', 
    'USER_MANAGEMENT', 'FEATURE_MANAGEMENT'
)
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled);

-- ===========================================
-- GLOBAL_FLAGS TABLE (Global features not tied to roles)
-- ===========================================
CREATE TABLE IF NOT EXISTS global_flags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_id BIGINT NOT NULL UNIQUE,
    enabled BOOLEAN NOT NULL DEFAULT false,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_global_flags_feature FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE,
    UNIQUE KEY uk_global_feature (feature_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert global flags (GLOBAL_USER_SIGNUP disabled, GLOBAL_APP_NAME enabled, TWO_FACTOR_AUTH enabled by default)
INSERT IGNORE INTO global_flags (feature_id, enabled)
SELECT id, CASE WHEN feature_key IN ('GLOBAL_USER_SIGNUP') THEN false ELSE true END
FROM features WHERE feature_key IN ('GLOBAL_USER_SIGNUP', 'GLOBAL_APP_NAME', 'TWO_FACTOR_AUTH');

-- Update enabled status if records already exist (idempotent)
UPDATE global_flags gf
JOIN features f ON gf.feature_id = f.id
SET gf.enabled = CASE WHEN f.feature_key = 'GLOBAL_USER_SIGNUP' THEN false ELSE true END
WHERE f.feature_key IN ('GLOBAL_USER_SIGNUP', 'GLOBAL_APP_NAME', 'TWO_FACTOR_AUTH');

-- ===========================================
-- DEFAULT DATA
-- ===========================================

-- Create admin user if not exists (idempotent)
-- Password: <YOUR_ADMIN_PASSWORD> (BCrypt hashed - generate your own hash)
-- IMPORTANT: This will NOT overwrite existing admin user data
-- TODO: Replace the email, username, and password hash below with your own values
-- use https://bcrypt-generator.com/ to create a bcrypt hash of your desired password with rounds=10
INSERT INTO users (email, first_name, last_name, username, password, role_id, must_change_password, status, failed_login_attempts, token_version)
SELECT '<YOUR_ADMIN_EMAIL>', '<Admin_FirstName>', '<Admin_LastName>', '<YOUR_ADMIN_USERNAME>',
       '<YOUR_BCRYPT_PASSWORD_HASH>',
       1, true, 'ACTIVE', 0, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM users WHERE username = '<YOUR_ADMIN_USERNAME>'
);

-- Set default status for existing users (safety update)
UPDATE users SET status = 'ACTIVE' WHERE status IS NULL;

-- Set default role for existing users without role (safety update)
UPDATE users SET role_id = 2 WHERE role_id IS NULL;

-- ===========================================
-- VERIFICATION QUERIES
-- ===========================================
-- Run these to verify the initialization was successful:

-- SELECT 'Roles:' AS info, COUNT(*) AS count FROM roles;
-- SELECT 'Users:' AS info, COUNT(*) AS count FROM users;
-- SELECT 'Features:' AS info, COUNT(*) AS count FROM features;
-- SELECT 'Feature Flags:' AS info, COUNT(*) AS count FROM feature_flags;
-- SELECT 'Global Flags:' AS info, COUNT(*) AS count FROM global_flags;
-- SELECT 'Admin User:' AS info, username, email FROM users WHERE username = '<YOUR_ADMIN_USERNAME>';
