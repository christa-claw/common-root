-- Per-user reader preferences (one row per user, created on first save).
--
-- Values reuse the ReaderLink token vocabulary (src token, mode token, order
-- token) so defaults, shareable links, and the saved reading position all speak
-- one language: applying preferences is synthesizing a link. last_position IS a
-- reader link's query string (what Copy-link emits, minus "/reader?"), captured
-- while "continue where I left off" is enabled.
CREATE TABLE user_preferences (
    user_id              VARCHAR(40)  NOT NULL PRIMARY KEY,
    default_source       VARCHAR(40)  NULL,      -- link src token, e.g. 'niv'
    default_mode         VARCHAR(20)  NULL,      -- link mode token, e.g. 'verses'
    default_order        VARCHAR(20)  NULL,      -- 'canon' | 'chrono' | 'tanakh'
    show_comments_panel  BOOLEAN      NOT NULL DEFAULT FALSE,
    show_comment_markers BOOLEAN      NOT NULL DEFAULT FALSE,
    resume_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    last_position        VARCHAR(2000) NULL,     -- reader-link query string
    ui_language          VARCHAR(10)  NULL,      -- 'en', 'fi', 'he', …
    updated_at           DATETIME     NOT NULL,
    CONSTRAINT fk_prefs_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);
