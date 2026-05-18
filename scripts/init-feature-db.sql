-- MySQL Database Initialization Script for Feature Service
CREATE DATABASE IF NOT EXISTS feature_db;
USE feature_db;

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

-- Insert master features (idempotent)
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
-- Note: role_id is a logical reference to the Identity Service's roles table
CREATE TABLE IF NOT EXISTS feature_flags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    feature_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_feature_flags_feature FOREIGN KEY (feature_id) REFERENCES features(id) ON DELETE CASCADE,
    UNIQUE KEY uk_feature_role (feature_id, role_id),
    INDEX idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Insert feature flags for USER role (role_id=2)
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

-- Insert global flags
INSERT IGNORE INTO global_flags (feature_id, enabled)
SELECT id, CASE WHEN feature_key IN ('GLOBAL_USER_SIGNUP') THEN false ELSE true END
FROM features WHERE feature_key IN ('GLOBAL_USER_SIGNUP', 'GLOBAL_APP_NAME', 'TWO_FACTOR_AUTH');

-- Update enabled status if records already exist (idempotent)
UPDATE global_flags gf
JOIN features f ON gf.feature_id = f.id
SET gf.enabled = CASE WHEN f.feature_key = 'GLOBAL_USER_SIGNUP' THEN false ELSE true END
WHERE f.feature_key IN ('GLOBAL_USER_SIGNUP', 'GLOBAL_APP_NAME', 'TWO_FACTOR_AUTH');
