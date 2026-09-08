-- Edit-aware reseeding (the "channel corrections must survive restarts" fix).
--
-- DataSeeder used to DELETE every system-account comment and reinsert from
-- arguments.json on each boot — wiping any correction a claimed channel had made
-- (exactly what the outreach email invites them to do). The seeder now MERGES:
--
--   locally_edited = TRUE   → row is preserved verbatim (the human edit wins over
--                             the ledger, including future re-extractions)
--   locally_edited = FALSE  → row is updated in place from the ledger (improved
--                             extractions still flow; acl_id survives untouched)
--   comment_tombstones      → a seeded comment DELETED via the editor stays dead:
--                             the reseed skips tombstoned public_ids instead of
--                             resurrecting them from the ledger
ALTER TABLE comments
    ADD COLUMN locally_edited BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE comment_tombstones (
    public_id  VARCHAR(40) NOT NULL,   -- the deleted comment's stable cmt_… id
    deleted_at DATETIME(6) NOT NULL,
    PRIMARY KEY (public_id)
) ENGINE=InnoDB;
