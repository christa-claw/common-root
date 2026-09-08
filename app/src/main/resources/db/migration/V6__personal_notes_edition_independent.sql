-- ============================================================
-- V6__personal_notes_edition_independent.sql
-- A personal note belongs to the VERSE, not the edition the reader happened to
-- have open — the same rule V5 set for comment references. So:
--   * source_id becomes optional provenance (which edition was open at write
--     time), no longer required and no longer part of a note's identity;
--   * the one-note-per-verse uniqueness now keys on the verse alone.
-- personal_notes is empty until this feature ships, so the re-key is safe.
-- ============================================================

ALTER TABLE personal_notes DROP INDEX uq_note_per_verse;

ALTER TABLE personal_notes MODIFY source_id VARCHAR(100) NULL;

ALTER TABLE personal_notes
    ADD CONSTRAINT uq_note_per_verse UNIQUE (user_id, book_code, chapter, verse);
