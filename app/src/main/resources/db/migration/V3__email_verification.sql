-- ============================================================
-- V3__email_verification.sql
-- Adds email verification support.
--   1. email_verification_token: a secure random token sent by email.
--      Cleared (set NULL) once the user clicks the link.
--   2. Existing accounts (the system user created by V2) are left
--      verified=TRUE so they continue to work.
--   3. New registrations start verified=FALSE until the link is clicked.
-- ============================================================

ALTER TABLE users
    ADD COLUMN email_verification_token VARCHAR(100) NULL DEFAULT NULL AFTER verified;
