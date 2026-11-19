-- ============================================================
-- V25__dictated_marker.sql
-- Marks a note or comment that its author SPOKE rather than typed (the phone
-- app's dictation, docs/api-spec.md §0). The recording itself is never stored
-- here: it stays on the device that made it, and this flag is what lets that
-- device show "listen" beside the text it belongs to. Existing rows were typed.
-- ============================================================
ALTER TABLE personal_notes ADD COLUMN dictated BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE comments       ADD COLUMN dictated BOOLEAN NOT NULL DEFAULT FALSE;
