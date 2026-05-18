-- MySQL Database Initialization Script for URL Service
CREATE DATABASE IF NOT EXISTS url_db;
USE url_db;

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
    INDEX idx_short_code (short_code),
    INDEX idx_user_id (user_id)
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
    INDEX idx_period (period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
