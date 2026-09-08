-- ============================================================================
-- Common Root? — CURRENT user-content MySQL schema (effective state after V1–V11)
-- Consolidated from app/src/main/resources/db/migration for review. NOT a migration;
-- it's the cumulative result of the Flyway history. (BaseX holds scripture; this DB
-- holds accounts, comments, notes, and the V11 access-control model.)
-- Generated 2026-07-11.
-- ============================================================================

-- ---------- users ----------  (V1 + V3 + V7 + V10 + V11)
CREATE TABLE users (
    id                        CHAR(40)     NOT NULL PRIMARY KEY,          -- usr-<uuidv7>
    email                     VARCHAR(255) NOT NULL UNIQUE,
    password_hash             VARCHAR(255) NOT NULL,                      -- BCrypt; sentinel "LOCKED-NO-LOGIN" for channel accts
    display_name              VARCHAR(100),
    created_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    verified                  BOOLEAN      NOT NULL DEFAULT FALSE,
    email_verification_token  VARCHAR(100) NULL DEFAULT NULL,             -- V3
    password_reset_token      VARCHAR(100) NULL DEFAULT NULL,             -- V7
    password_reset_expires    DATETIME     NULL DEFAULT NULL,             -- V7
    is_admin                  BOOLEAN      NOT NULL DEFAULT FALSE,        -- V1 (kept for 1 release, superseded by role)
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    is_system                 BOOLEAN      NOT NULL DEFAULT FALSE,        -- V10 (channel accounts)
    role ENUM('consumer','contributor','admin','superuser') NOT NULL DEFAULT 'consumer'  -- V11
);
CREATE INDEX idx_users_email     ON users (email);
CREATE INDEX ix_users_is_system  ON users (is_system);
CREATE INDEX ix_users_role       ON users (role);

-- ---------- comments ----------  (V1 + V8 + V11)
CREATE TABLE comments (
    id                CHAR(40) NOT NULL PRIMARY KEY,                      -- cmt-<uuidv7> (reseeded for system accts)
    user_id           CHAR(40) NOT NULL,                                 -- owner (channel acct or real user)
    content           TEXT     NOT NULL,
    is_public         BOOLEAN  NOT NULL DEFAULT FALSE,
    moderation_status ENUM('approved','pending','rejected') NOT NULL DEFAULT 'approved',
    rejection_reason  VARCHAR(1000),
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    public_id         VARCHAR(40) NULL,                                  -- V8 stable permalink (cmt_<uuid5> for seeds)
    acl_id            CHAR(40)    NULL,                                  -- V11 custom (instance) ACL; NULL = inherit
    CONSTRAINT fk_comments_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ux_comments_public_id UNIQUE (public_id),                 -- V8
    CONSTRAINT fk_comments_acl  FOREIGN KEY (acl_id)  REFERENCES acls(id) ON DELETE SET NULL  -- V11
);
CREATE INDEX idx_comments_user       ON comments (user_id);
CREATE INDEX idx_comments_moderation ON comments (is_public, moderation_status);

-- ---------- comment_references ----------  (V1 + V4 + V5)
CREATE TABLE comment_references (
    id          CHAR(40) NOT NULL PRIMARY KEY,                           -- ref-<uuidv7>
    comment_id  CHAR(40) NOT NULL,
    position    INT      NOT NULL DEFAULT 0,
    ref_type    ENUM('internal','external') NOT NULL,
    source_id   VARCHAR(100),                                           -- internal: provenance edition (nullable, V5)
    book_code   VARCHAR(10),
    chapter     INT,
    verse       INT,
    video_url   VARCHAR(2048) NULL,                                     -- V4 timecoded YouTube link (internal refs)
    url         VARCHAR(2048),                                          -- external
    label       VARCHAR(255),
    description VARCHAR(1000),
    CONSTRAINT fk_refs_comment FOREIGN KEY (comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    CONSTRAINT chk_internal CHECK (ref_type != 'internal'               -- V5: source_id NO LONGER required
        OR (book_code IS NOT NULL AND chapter IS NOT NULL AND verse IS NOT NULL)),
    CONSTRAINT chk_external CHECK (ref_type != 'external'
        OR (url IS NOT NULL AND label IS NOT NULL))
);
CREATE INDEX idx_refs_comment ON comment_references (comment_id, position);
CREATE INDEX idx_refs_verse   ON comment_references (source_id, book_code, chapter, verse);

-- ---------- personal_notes ----------  (V1 + V6)
CREATE TABLE personal_notes (
    id          CHAR(40)     NOT NULL PRIMARY KEY,                       -- nte-<uuidv7>
    user_id     CHAR(40)     NOT NULL,
    source_id   VARCHAR(100) NULL,                                       -- V6: optional provenance
    book_code   VARCHAR(10)  NOT NULL,
    chapter     INT          NOT NULL,
    verse       INT          NOT NULL,
    content     TEXT         NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_note_per_verse UNIQUE (user_id, book_code, chapter, verse)  -- V6: edition-independent
);
CREATE INDEX idx_notes_verse ON personal_notes (source_id, book_code, chapter, verse);
CREATE INDEX idx_notes_user  ON personal_notes (user_id);

-- ---------- blocked_domains ----------  (V1)
CREATE TABLE blocked_domains (
    id          CHAR(40)     NOT NULL PRIMARY KEY,                       -- blk-<uuidv7>
    domain      VARCHAR(255) NOT NULL UNIQUE,
    reason      VARCHAR(500),
    blocked_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    blocked_by  CHAR(40)     NOT NULL,
    CONSTRAINT fk_blocked_by FOREIGN KEY (blocked_by) REFERENCES users(id)
);

-- ---------- user_preferences ----------  (V9)
CREATE TABLE user_preferences (
    user_id              VARCHAR(40)  NOT NULL PRIMARY KEY,
    default_source       VARCHAR(40)  NULL,
    default_mode         VARCHAR(20)  NULL,
    default_order        VARCHAR(20)  NULL,                              -- 'canon' | 'chrono' | 'tanakh'
    show_comments_panel  BOOLEAN      NOT NULL DEFAULT FALSE,
    show_comment_markers BOOLEAN      NOT NULL DEFAULT FALSE,
    resume_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    last_position        VARCHAR(2000) NULL,
    ui_language          VARCHAR(10)  NULL,
    updated_at           DATETIME     NOT NULL,
    CONSTRAINT fk_prefs_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- ============================================================================
-- V11 ACCESS-CONTROL MODEL  (roles above are on users; the ACL plane is below)
-- ============================================================================

-- ---------- access_groups ----------  (owner-groups; created on CLAIM, not ingest)
CREATE TABLE access_groups (
    id                CHAR(40)     NOT NULL PRIMARY KEY,                  -- grp-<uuidv7>
    name              VARCHAR(100) NOT NULL UNIQUE,                      -- "channel-owners:<channelUserId>"
    is_system         BOOLEAN      NOT NULL DEFAULT TRUE,
    owner_accessor_id CHAR(40)     NULL,                                 -- ACCESSOR (user OR group); soft ref, no FK
    default_acl_id    CHAR(40)     NULL,                                 -- the org's DEFAULT ACL
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    CONSTRAINT fk_access_groups_default_acl FOREIGN KEY (default_acl_id) REFERENCES acls(id) ON DELETE SET NULL
);

-- ---------- group_memberships ----------  (ACCESSORS ∈ access_groups; member = user OR group = NESTING)
CREATE TABLE group_memberships (
    group_id           CHAR(40) NOT NULL,
    member_accessor_id CHAR(40) NOT NULL,                                -- a user OR a group (nesting)
    member_kind        ENUM('user','group') NOT NULL DEFAULT 'user',
    PRIMARY KEY (group_id, member_accessor_id),
    KEY ix_gm_member (member_accessor_id),
    CONSTRAINT fk_gm_group FOREIGN KEY (group_id) REFERENCES access_groups(id) ON DELETE CASCADE
    -- No member FK (member points at users OR access_groups); cleanup is app-level.
);

-- ---------- acls ----------  (named/shared + per-comment custom instances)
CREATE TABLE acls (
    id                CHAR(40)     NOT NULL PRIMARY KEY,                  -- acl-<uuidv7>
    name              VARCHAR(100) NOT NULL UNIQUE,                      -- MACHINE KEY: "channel-comments:<id>", "comment:<id>", "system-default"
    label             VARCHAR(200) NULL,                                 -- human name (named ACLs); NULL for custom
    is_custom         BOOLEAN      NOT NULL DEFAULT FALSE,               -- TRUE = per-comment instance ACL
    is_system         BOOLEAN      NOT NULL DEFAULT FALSE,               -- TRUE = seeded/reserved
    owner_accessor_id CHAR(40)     NULL,                                 -- ACCESSOR (user OR group); soft ref, no FK
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    KEY ix_acls_is_custom (is_custom)                                    -- admin "named ACLs" list filters is_custom=false
);

-- ---------- aces ----------  (one ACL = many entries)
CREATE TABLE aces (
    id            CHAR(40) NOT NULL PRIMARY KEY,                          -- ace-<uuidv7>
    acl_id        CHAR(40) NOT NULL,
    accessor_kind ENUM('user','group','role','world','owner') NOT NULL,
    accessor_id   VARCHAR(100) NULL,                                     -- user/group id or role name; NULL for world/owner
    entry_type    ENUM('permit','restriction','required') NOT NULL DEFAULT 'permit',
    basic_level   ENUM('none','browse','read','write','delete') NOT NULL,
    ext_perms     VARCHAR(255) NULL,                                     -- additive: "moderate,change_acl,change_owner"
    sort_order    INT NOT NULL DEFAULT 0,
    KEY ix_aces_acl (acl_id),
    CONSTRAINT fk_aces_acl FOREIGN KEY (acl_id) REFERENCES acls(id) ON DELETE CASCADE
);

-- Seeded ACLs at runtime (not in migrations; DataSeeder / AccessService.claimChannel):
--   system-default : {world READ, owner DELETE}                         -- fallback when org unknown
--   channel-comments:<id> (org default, on CLAIM) : {world NONE, owner DELETE, <org-group> WRITE}
--   comment:<id> (custom, copy-on-write) : clone of the effective ACL, is_custom=true
--
-- Also present: flyway_schema_history (Flyway's own bookkeeping table).
