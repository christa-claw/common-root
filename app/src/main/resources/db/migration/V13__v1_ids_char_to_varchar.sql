-- Finish aligning the schema on VARCHAR(40) ids (companion to V12).
--
-- The V1 baseline created every table with CHAR(40) ids, but all String-id entities map
-- to VARCHAR(40) (@Column(length = 40) → VARCHAR). This never mattered while ddl-auto=none;
-- switching to ddl-auto=validate (for fail-fast boots) surfaced it table by table
-- (access_groups, then blocked_domains, …). V12 converted the V11 tables; this converts the
-- remaining V1 tables so the whole schema is uniformly VARCHAR(40).
--
-- Every affected id is a typed uuidv7 exactly 40 chars long ({prefix}-{36-char uuid}), so the
-- CHAR→VARCHAR change is lossless (no padding to strip, no truncation). FKs must match types on
-- both sides, so the FKs spanning these columns are dropped, the columns widened, then the FKs
-- recreated with their original ON DELETE behaviour.

-- Drop every FK that references a column being converted ---------------------
-- (all four child-side FKs, PLUS fk_prefs_user which references users.id from the
-- V9 user_preferences table — miss it and MODIFY users.id is refused).
ALTER TABLE comments           DROP FOREIGN KEY fk_comments_user;
ALTER TABLE personal_notes     DROP FOREIGN KEY fk_notes_user;
ALTER TABLE blocked_domains    DROP FOREIGN KEY fk_blocked_by;
ALTER TABLE comment_references DROP FOREIGN KEY fk_refs_comment;
ALTER TABLE user_preferences   DROP FOREIGN KEY fk_prefs_user;

-- CHAR(40) -> VARCHAR(40), preserving PK / nullability ------------------------
ALTER TABLE users
    MODIFY COLUMN id VARCHAR(40) NOT NULL;

ALTER TABLE comments
    MODIFY COLUMN id      VARCHAR(40) NOT NULL,
    MODIFY COLUMN user_id VARCHAR(40) NOT NULL;

ALTER TABLE comment_references
    MODIFY COLUMN id         VARCHAR(40) NOT NULL,
    MODIFY COLUMN comment_id VARCHAR(40) NOT NULL;

ALTER TABLE personal_notes
    MODIFY COLUMN id      VARCHAR(40) NOT NULL,
    MODIFY COLUMN user_id VARCHAR(40) NOT NULL;

ALTER TABLE blocked_domains
    MODIFY COLUMN id         VARCHAR(40) NOT NULL,
    MODIFY COLUMN blocked_by VARCHAR(40) NOT NULL;

-- Recreate the FKs unchanged (now VARCHAR(40) on both sides) ------------------
ALTER TABLE comments
    ADD CONSTRAINT fk_comments_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE personal_notes
    ADD CONSTRAINT fk_notes_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE blocked_domains
    ADD CONSTRAINT fk_blocked_by FOREIGN KEY (blocked_by)
        REFERENCES users (id);

ALTER TABLE comment_references
    ADD CONSTRAINT fk_refs_comment FOREIGN KEY (comment_id)
        REFERENCES comments (id) ON DELETE CASCADE;

ALTER TABLE user_preferences
    ADD CONSTRAINT fk_prefs_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE;
