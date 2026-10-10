-- ============================================================
-- V21__xref_marker_preference.sql
-- Reader preference: show cross-reference markers (the cross badge after a
-- verse) in new columns. Off by default, like show_comment_markers: some
-- readers want nothing inserted into the scripture text.
-- ============================================================
ALTER TABLE user_preferences
    ADD COLUMN show_xref_markers BOOLEAN NOT NULL DEFAULT FALSE AFTER show_comment_markers;
