-- V15: human-facing title + description for access groups.
-- The admin accessors view lets admins create non-system groups; `label` is the
-- display title (same idea as acls.label), `description` says what the group is for.
-- Both NULL for existing rows — channel member groups keep deriving their display
-- name from the channel account.

ALTER TABLE access_groups
    ADD COLUMN label       VARCHAR(100) NULL AFTER name,
    ADD COLUMN description VARCHAR(500) NULL AFTER label;
