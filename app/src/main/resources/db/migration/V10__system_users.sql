-- System-owned "channel" accounts: one per ingested channel (e.g. "Apologia
-- Studios"), owning that channel's auto-generated argument comments.
--
-- They cannot log in — DataSeeder sets a non-bcrypt sentinel password_hash that
-- PasswordEncoder.matches() can never satisfy — and DataSeeder re-seeds THEIR
-- comments on every startup, exactly as it did for the single legacy seed user.
-- Real users' comments are never touched. The flag is what lets the reseed
-- target system accounts precisely, and it's the hook for a future "claim your
-- channel" flow: flipping is_system off turns a channel account into a real
-- login with its comments intact.
ALTER TABLE users ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- The pre-existing single seed user (V2) becomes a system account, so its old
-- one-user seed is cleaned up on the next reseed and re-created per channel.
UPDATE users SET is_system = TRUE
 WHERE id = 'usr-00000000-0000-7000-8000-000000000001';

CREATE INDEX ix_users_is_system ON users (is_system);
