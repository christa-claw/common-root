-- ============================================================
-- V20__cross_reference.sql
-- Verse -> related-verse cross-references (OpenBible.info, CC BY 4.0).
--
-- Reference data, deliberately NOT comments: ~340k rows would swamp the
-- comments panel, the mute list, moderation and the API's meaning of
-- "comment". Anchors are USFM book code + chapter + verse in the canonical
-- (KJV) numbering, so a reference is edition-independent like V5 comments.
--
-- The natural key (source, from, to) is what XrefSeeder upserts on, so a
-- re-seed keeps each row's id and only refreshes its votes.
-- ============================================================

CREATE TABLE cross_reference (
    id             CHAR(40)    NOT NULL PRIMARY KEY,   -- xrf-{uuidv7}
    source         VARCHAR(20) NOT NULL,               -- 'openbible'
    from_book      VARCHAR(10) NOT NULL,
    from_chapter   INT         NOT NULL,
    from_verse     INT         NOT NULL,
    to_book        VARCHAR(10) NOT NULL,
    to_chapter     INT         NOT NULL,
    to_verse       INT         NOT NULL,
    to_end_chapter INT         NULL,                   -- range end, e.g. Prov 8:22-30
    to_end_verse   INT         NULL,
    votes          INT         NOT NULL DEFAULT 0,
    UNIQUE KEY uq_xref_natural (source, from_book, from_chapter, from_verse,
                                to_book, to_chapter, to_verse),
    INDEX ix_xref_from (from_book, from_chapter, votes)
);

-- Checksum gate: the seeder skips a source whose file hash is unchanged.
CREATE TABLE cross_reference_seed (
    source    VARCHAR(20) NOT NULL PRIMARY KEY,
    sha256    CHAR(64)    NOT NULL,
    row_count INT         NOT NULL,
    seeded_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
