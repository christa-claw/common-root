-- Roles & access control (design: docs/access-control.md).
--
-- TWO co-existing planes, à la Documentum:
--   1. Role plane  — a system-wide, cumulative ladder stored as users.role
--                    (consumer < contributor < admin < superuser).
--   2. ACL plane   — per-object grants via acls + aces, accessors being users,
--                    groups, roles, or the specials 'world'/'owner'.
-- Effective(user,object,action) = MAX(role-plane, ACL grants matched), then
-- restrictions; the OWNER FLOOR is 'delete' and the Super User inherits AT LEAST
-- the owner's privilege on every object.
--
-- is_admin is intentionally KEPT for one release (read-through) and dropped in a
-- later migration once nothing reads it.

-- 1. Role plane -------------------------------------------------------------
-- 'super' is a Java keyword, so the top tier is spelled 'superuser' to match the
-- Role enum constant (enum constants are lowercase to mirror the ENUM, exactly
-- as Comment.ModerationStatus does).
ALTER TABLE users
    ADD COLUMN role ENUM('consumer','contributor','admin','superuser')
        NOT NULL DEFAULT 'consumer';

-- Carry the existing boolean across: current admins become role='admin'.
UPDATE users SET role = 'admin' WHERE is_admin = TRUE;

-- The single seeded Super User (the operator / root of trust). Super is never
-- grantable through the UI — it is set here (and only here) by email.
UPDATE users SET role = 'superuser' WHERE email = 'christa.claw@proton.me';

CREATE INDEX ix_users_role ON users (role);

-- 2. Groups (owner-groups) --------------------------------------------------
-- Narrowly the ACL-plane groups (e.g. per-channel owner-groups). NOT the
-- tradition grouping in docs/groups.md (that is a separate users.group_id FK).
-- Named 'access_groups' (not 'groups') because GROUPS is a reserved word in
-- MySQL 8, and to leave the bare 'groups' name to the tradition feature.
CREATE TABLE access_groups (
    id            CHAR(40)     NOT NULL,          -- grp-<uuidv7>
    name          VARCHAR(100) NOT NULL,          -- e.g. "channel-owners:<channelUserId>"
    is_system     BOOLEAN      NOT NULL DEFAULT TRUE,
    owner_accessor_id CHAR(40) NULL,              -- ACCESSOR (user OR group) admin/owner; soft ref, no FK
    default_acl_id CHAR(40)    NULL,              -- the org's DEFAULT ACL (FK added after acls exists)
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_access_groups_name (name)
) ENGINE=InnoDB;

-- Members are ACCESSORS: a user OR another group (nesting). No FK on the member — it points
-- at users OR access_groups; membership cleanup on member delete is app-level (GroupService).
CREATE TABLE group_memberships (
    group_id           CHAR(40) NOT NULL,
    member_accessor_id CHAR(40) NOT NULL,
    member_kind        ENUM('user','group') NOT NULL DEFAULT 'user',
    PRIMARY KEY (group_id, member_accessor_id),
    KEY ix_gm_member (member_accessor_id),
    CONSTRAINT fk_gm_group FOREIGN KEY (group_id)
        REFERENCES access_groups (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- 3. ACL plane --------------------------------------------------------------
CREATE TABLE acls (
    id            CHAR(40)     NOT NULL,          -- acl-<uuidv7>
    name          VARCHAR(100) NOT NULL,          -- MACHINE KEY/slug: "channel-comments:<id>", "comment:<id>", "system-default"
    label         VARCHAR(200) NULL,              -- human-readable name/description (named/shared ACLs; NULL for custom)
    is_custom     BOOLEAN      NOT NULL DEFAULT FALSE,  -- TRUE = per-object instance ACL (a comment's copy)
    is_system     BOOLEAN      NOT NULL DEFAULT FALSE,  -- TRUE = seeded/reserved, not user-deletable
    owner_accessor_id CHAR(40) NULL,               -- ACCESSOR (user OR group); soft ref, no FK
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_acls_name (name),
    KEY ix_acls_is_custom (is_custom)             -- the admin "named ACLs" list filters is_custom=false
) ENGINE=InnoDB;

-- One ACL = many entries. basic_level is the reduced Documentum ladder
-- (none < browse < read < write < delete; delete is the top basic level and the
-- owner floor). ext_perms is a comma-set of additive extended permissions
-- (moderate, change_acl, change_owner). accessor_id holds a user/group id or a
-- role name; NULL for the specials 'world'/'owner'.
CREATE TABLE aces (
    id            CHAR(40) NOT NULL,              -- ace-<uuidv7>
    acl_id        CHAR(40) NOT NULL,
    accessor_kind ENUM('user','users','group','role','world','owner') NOT NULL,
    accessor_id   VARCHAR(100) NULL,
    entry_type    ENUM('permit','restriction','required') NOT NULL DEFAULT 'permit',
    basic_level   ENUM('none','browse','read','write','delete') NOT NULL,
    ext_perms     VARCHAR(255) NULL,
    sort_order    INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_aces_acl (acl_id),
    CONSTRAINT fk_aces_acl FOREIGN KEY (acl_id)
        REFERENCES acls (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- An object with no ACL of its own falls back to its ORG's default ACL, and failing that a
-- SYSTEM default ACL (docs/access-control.md §4). Added now that acls exists.
ALTER TABLE access_groups
    ADD CONSTRAINT fk_access_groups_default_acl FOREIGN KEY (default_acl_id)
        REFERENCES acls (id) ON DELETE SET NULL;

-- A comment MAY carry its own CUSTOM ACL (Documentum instance ACL). NULL = inherit the
-- org default → system default. Set only when someone edits security "in the context of
-- this comment" (copy-on-write; see docs/access-control.md §4a). A custom ACL is a
-- non-system acls row named "comment:<commentId>", referenced by exactly one comment.
ALTER TABLE comments
    ADD COLUMN acl_id CHAR(40) NULL,
    ADD CONSTRAINT fk_comments_acl FOREIGN KEY (acl_id)
        REFERENCES acls (id) ON DELETE SET NULL;
