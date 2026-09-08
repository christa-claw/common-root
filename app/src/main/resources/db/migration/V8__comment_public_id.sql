-- Stable public permalink id for comments (?comment=cmt_… deep links).
--
-- The row id (cmt-<uuidv7>) is NOT stable for the seeded arguments: DataSeeder
-- deletes and re-persists every system-user comment at startup, minting fresh
-- row ids each time. The permalink therefore lives in its own column, filled
-- from arguments.json's deterministic `id` (cmt_<uuid5>, see comment_id.py) for
-- seeded rows, and defaulting to the row id for user-authored comments (those
-- are never reseeded, so their row id IS stable).
ALTER TABLE comments ADD COLUMN public_id VARCHAR(40) NULL;

-- Backfill existing rows. Only user-authored comments truly need this (the
-- system seed is replaced on next startup with public_id set from the file),
-- but filling every row keeps the column NULL-free from day one.
UPDATE comments SET public_id = id WHERE public_id IS NULL;

-- Unique: the permalink must resolve to at most one comment. (MySQL permits
-- multiple NULLs under a UNIQUE index, so a future NULL wouldn't violate it.)
ALTER TABLE comments ADD CONSTRAINT ux_comments_public_id UNIQUE (public_id);
