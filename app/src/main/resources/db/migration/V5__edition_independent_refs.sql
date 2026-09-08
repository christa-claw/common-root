-- ============================================================
-- V5__edition_independent_refs.sql
-- Allow internal comment references with a NULL source_id.
--
-- A NULL source_id means the reference is edition-independent:
-- a comment anchored to John 8:58 belongs to the verse itself and
-- is shown in every Bible edition (likewise surah/ayah refs across
-- Qur'an editions). The original constraint demanded a source_id,
-- which contradicts that design; book/chapter/verse remain required.
-- ============================================================

ALTER TABLE comment_references DROP CHECK chk_internal;

ALTER TABLE comment_references ADD CONSTRAINT chk_internal CHECK (
    ref_type != 'internal'
    OR (book_code IS NOT NULL AND chapter IS NOT NULL AND verse IS NOT NULL));
