-- ============================================================
-- V22__saved_searches.sql
-- Private saved full-text searches: a query string per user, re-run from the
-- search dialog. Full-text search takes only a query (no scope filters), so the
-- query text is the whole of what is stored. One row per (user, query).
-- ============================================================
CREATE TABLE saved_searches (
    id          VARCHAR(40)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(40)  NOT NULL,
    query_text  VARCHAR(200) NOT NULL,
    created_at  DATETIME     NOT NULL,
    CONSTRAINT uq_saved_search UNIQUE (user_id, query_text),
    CONSTRAINT fk_saved_search_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE
);
