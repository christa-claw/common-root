-- ============================================================
-- V7__password_reset.sql
-- Standard "forgot password" support: a one-time reset token emailed to the
-- user, valid for a limited window. Mirrors V3's email-verification token.
--   * password_reset_token   : secure random token carried in the reset link.
--   * password_reset_expires : the token stops working after this instant.
-- Both NULL when no reset is in progress; cleared once the password changes.
-- ============================================================

ALTER TABLE users
    ADD COLUMN password_reset_token VARCHAR(100) NULL DEFAULT NULL AFTER email_verification_token;

ALTER TABLE users
    ADD COLUMN password_reset_expires DATETIME NULL DEFAULT NULL AFTER password_reset_token;
