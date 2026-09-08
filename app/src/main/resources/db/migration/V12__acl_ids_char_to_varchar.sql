-- Align the V11 access-control id columns with the rest of the schema.
--
-- V11 created access_groups / group_memberships / acls / aces (and comments.acl_id)
-- with CHAR(40) ids, but every other id column in this database is VARCHAR(40)
-- (users.id, comments.id, …, all mapped as @Column(length = 40) → VARCHAR). Hibernate
-- schema-validation maps those String ids to VARCHAR and rejects the CHAR columns:
--
--   Schema-validation: wrong column type encountered in column [id] in table
--   [access_groups]; found [char (Types#CHAR)], but expecting [varchar(40) …]
--
-- This migration converts the new columns to VARCHAR(40) so the whole schema is
-- consistent. FKs require both sides to share a type, so each affected FK is dropped,
-- the parent + child columns are widened, then the FK is recreated unchanged. Pure
-- type change on uuidv7 ids (never trailing-space sensitive) — no data is touched.

-- Drop the four FKs that span the columns being converted -------------------
ALTER TABLE group_memberships DROP FOREIGN KEY fk_gm_group;
ALTER TABLE access_groups     DROP FOREIGN KEY fk_access_groups_default_acl;
ALTER TABLE aces              DROP FOREIGN KEY fk_aces_acl;
ALTER TABLE comments          DROP FOREIGN KEY fk_comments_acl;

-- CHAR(40) -> VARCHAR(40), preserving each column's nullability ---------------
ALTER TABLE access_groups
    MODIFY COLUMN id                VARCHAR(40) NOT NULL,
    MODIFY COLUMN owner_accessor_id VARCHAR(40) NULL,
    MODIFY COLUMN default_acl_id    VARCHAR(40) NULL;

ALTER TABLE group_memberships
    MODIFY COLUMN group_id           VARCHAR(40) NOT NULL,
    MODIFY COLUMN member_accessor_id VARCHAR(40) NOT NULL;

ALTER TABLE acls
    MODIFY COLUMN id                VARCHAR(40) NOT NULL,
    MODIFY COLUMN owner_accessor_id VARCHAR(40) NULL;

ALTER TABLE aces
    MODIFY COLUMN id     VARCHAR(40) NOT NULL,
    MODIFY COLUMN acl_id VARCHAR(40) NOT NULL;

ALTER TABLE comments
    MODIFY COLUMN acl_id VARCHAR(40) NULL;

-- Recreate the FKs unchanged (now VARCHAR(40) on both sides) ------------------
ALTER TABLE group_memberships
    ADD CONSTRAINT fk_gm_group FOREIGN KEY (group_id)
        REFERENCES access_groups (id) ON DELETE CASCADE;

ALTER TABLE access_groups
    ADD CONSTRAINT fk_access_groups_default_acl FOREIGN KEY (default_acl_id)
        REFERENCES acls (id) ON DELETE SET NULL;

ALTER TABLE aces
    ADD CONSTRAINT fk_aces_acl FOREIGN KEY (acl_id)
        REFERENCES acls (id) ON DELETE CASCADE;

ALTER TABLE comments
    ADD CONSTRAINT fk_comments_acl FOREIGN KEY (acl_id)
        REFERENCES acls (id) ON DELETE SET NULL;
