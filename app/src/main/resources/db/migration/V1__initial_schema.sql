-- ============================================================
-- Religious Texts Platform — Initial Schema
-- V1__initial_schema.sql
-- ============================================================
-- Uses typed UUID v7 IDs in format: {prefix}-{uuidv7}
--   usr- = users
--   cmt- = comments
--   ref- = comment_references
--   nte- = personal_notes
--   blk- = blocked_domains
-- ============================================================

CREATE TABLE users (
    id            CHAR(40)     NOT NULL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name  VARCHAR(100),
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_admin      BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX idx_users_email ON users (email);

-- ============================================================

CREATE TABLE comments (
    id                CHAR(40)     NOT NULL PRIMARY KEY,
    user_id           CHAR(40)     NOT NULL,
    content           TEXT         NOT NULL,
    is_public         BOOLEAN      NOT NULL DEFAULT FALSE,
    moderation_status ENUM('approved','pending','rejected') NOT NULL DEFAULT 'approved',
    rejection_reason  VARCHAR(1000),
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Comment lifecycle:
--   Private (is_public=false):                visible to author only, moderation irrelevant
--   Public pending  (moderation=pending):     has external links, awaiting admin review
--   Public approved (moderation=approved):    visible to all readers
--   Public rejected (moderation=rejected):    author sees rejection_reason, reverts to private view
--   Retracted: author sets is_public=false,   immediately removed from public view

CREATE INDEX idx_comments_user       ON comments (user_id);
CREATE INDEX idx_comments_moderation ON comments (is_public, moderation_status);

-- ============================================================

CREATE TABLE comment_references (
    id          CHAR(40)      NOT NULL PRIMARY KEY,
    comment_id  CHAR(40)      NOT NULL,
    position    INT           NOT NULL DEFAULT 0,
    ref_type    ENUM('internal','external') NOT NULL,

    -- Internal: verse in our database (null for external)
    source_id   VARCHAR(100),
    book_code   VARCHAR(10),
    chapter     INT,
    verse       INT,

    -- External: link to outside source (null for internal)
    url         VARCHAR(2048),
    label       VARCHAR(255),
    description VARCHAR(1000),

    CONSTRAINT fk_refs_comment FOREIGN KEY (comment_id)
        REFERENCES comments(id) ON DELETE CASCADE,
    CONSTRAINT chk_internal CHECK (
        ref_type != 'internal'
        OR (source_id IS NOT NULL AND book_code IS NOT NULL
            AND chapter IS NOT NULL AND verse IS NOT NULL)),
    CONSTRAINT chk_external CHECK (
        ref_type != 'external'
        OR (url IS NOT NULL AND label IS NOT NULL))
);

CREATE INDEX idx_refs_comment ON comment_references (comment_id, position);
CREATE INDEX idx_refs_verse   ON comment_references (source_id, book_code, chapter, verse);

-- ============================================================

CREATE TABLE personal_notes (
    id          CHAR(40)     NOT NULL PRIMARY KEY,
    user_id     CHAR(40)     NOT NULL,
    source_id   VARCHAR(100) NOT NULL,
    book_code   VARCHAR(10)  NOT NULL,
    chapter     INT          NOT NULL,
    verse       INT          NOT NULL,
    content     TEXT         NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uq_note_per_verse (user_id, source_id, book_code, chapter, verse)
);

CREATE INDEX idx_notes_verse ON personal_notes (source_id, book_code, chapter, verse);
CREATE INDEX idx_notes_user  ON personal_notes (user_id);

-- ============================================================

CREATE TABLE blocked_domains (
    id          CHAR(40)     NOT NULL PRIMARY KEY,
    domain      VARCHAR(255) NOT NULL UNIQUE,
    reason      VARCHAR(500),
    blocked_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    blocked_by  CHAR(40)     NOT NULL,
    CONSTRAINT fk_blocked_by FOREIGN KEY (blocked_by) REFERENCES users(id)
);
