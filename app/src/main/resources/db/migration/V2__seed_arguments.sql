-- ============================================================
-- V2__seed_arguments.sql
-- Pre-populates comments from curated channel arguments.
-- Inserts a system user, then one comment + references per entry.
-- Run after V1__initial_schema.sql
-- ============================================================

-- System user for pre-populated content
INSERT INTO users (id, email, password_hash, display_name, verified, is_admin, is_active)
VALUES (
    'usr-00000000-0000-7000-8000-000000000001',
    'system@religioustext.platform',
    '$2a$12$PLACEHOLDER_HASH_NOT_FOR_LOGIN',
    'Platform',
    TRUE, TRUE, TRUE
);

-- ============================================================
-- Arguments are seeded programmatically via DataSeeder.java
-- which reads transcripts/arguments.json at startup.
-- This file exists as a Flyway marker only.
-- ============================================================
