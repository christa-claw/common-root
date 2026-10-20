-- ============================================================
-- V24__highlights.sql
-- Private verse highlights: one colour per (user, verse), edition-independent like
-- personal_notes (V6) — a highlight on John 3:16 shows in every edition. The colour
-- is a key (yellow, green, ...); the palette lives in the app so it can be themed.
-- Also the reader preference that switches highlights on or off (on by default).
-- ============================================================
CREATE TABLE highlights (
    id          VARCHAR(40) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(40) NOT NULL,
    book_code   VARCHAR(10) NOT NULL,
    chapter     INT         NOT NULL,
    verse       INT         NOT NULL,
    colour      VARCHAR(16) NOT NULL,
    created_at  DATETIME    NOT NULL,
    updated_at  DATETIME    NOT NULL,
    CONSTRAINT uq_highlight_per_verse UNIQUE (user_id, book_code, chapter, verse),
    CONSTRAINT fk_highlight_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);

ALTER TABLE user_preferences
    ADD COLUMN show_highlights BOOLEAN NOT NULL DEFAULT TRUE AFTER show_xref_markers;
