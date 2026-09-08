-- Seed-ledger scoping (the "two ledgers must not sweep each other" fix).
--
-- A second seeder is arriving (NotesSeeder: translator footnotes from
-- transcripts/notes-<edition>.json, e.g. the 85 Lutherbibel 1912 notes). Both
-- seeders create comments owned by is_system accounts, and DataSeeder's V14
-- merge loads + orphan-sweeps EVERY system-account comment — so without a
-- discriminator each seeder would delete the other's rows on every boot.
--
-- seed_ledger names the ledger a seeded row came from:
--   'arguments'        transcripts/arguments.json   (DataSeeder)
--   'notes-<edition>'  transcripts/notes-<ed>.json  (NotesSeeder, e.g. 'notes-lut1912')
--   NULL               user-authored comment        (never touched by any seeder)
-- Each seeder's merge and orphan sweep filter on ITS OWN value.
--
-- Read side: rows of a 'notes-%' ledger are edition-BOUND — a 1912 footnote
-- renders only under a Lutherbibel 1912 column (its reference's source_id names
-- that edition). Every other comment stays edition-independent per the V5 rule
-- (source_id is provenance only there).
ALTER TABLE comments ADD COLUMN seed_ledger VARCHAR(40) NULL;

-- Backfill: every system-account comment that exists today came from
-- arguments.json (the notes ledgers have never been seeded), so stamping them
-- all 'arguments' is exact, not a guess.
UPDATE comments c
  JOIN users u ON c.user_id = u.id
   SET c.seed_ledger = 'arguments'
 WHERE u.is_system = TRUE;

CREATE INDEX ix_comments_seed_ledger ON comments (seed_ledger);
