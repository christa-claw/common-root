-- ============================================================
-- V23__pending_email_change.sql
-- Self-service email change: the new address waits here until its owner clicks
-- the confirmation link mailed to it, then swaps into users.email. Until then
-- the account keeps signing in with the old address.
-- ============================================================
ALTER TABLE users
    ADD COLUMN pending_email         VARCHAR(255) NULL,
    ADD COLUMN pending_email_token   VARCHAR(100) NULL,
    ADD COLUMN pending_email_expires DATETIME     NULL,
    ADD INDEX idx_users_pending_email_token (pending_email_token);
