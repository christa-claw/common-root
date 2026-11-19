-- Guest API keys (docs/api-spec.md §2.1): a key with no account.
--
-- The phone app asks for one on first launch so a reader can start reading
-- without signing up. A guest key reads; it never writes, because there is no
-- account to write as. Each install gets its own key, so each carries its own
-- quota and can be revoked alone — unlike one key built into the app, which
-- anyone could lift from the APK and spend for everybody.
--
-- user_id NULL is what makes a key a guest key. The foreign key stays: NULL
-- satisfies it, and an account key still has to name a real account.

ALTER TABLE api_keys MODIFY user_id VARCHAR(40) NULL;
