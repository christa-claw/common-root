-- Religious Texts Platform - MySQL Schema
-- Handles user comments, session state, ingestion job log
-- Religious texts themselves live in eXist-db

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

USE religioustext;

-- ── User Comments ───────────────────────────────────────────────────
-- Intentionally loose reference to text source.
-- No foreign key into eXist-db — comments survive source changes.
CREATE TABLE IF NOT EXISTS user_comment (
     id                 CHAR(36)        NOT NULL DEFAULT (UUID())
    , text_source_id    VARCHAR(100)    NOT NULL COMMENT 'eXist-db document id e.g. bible-kjv-1611'
    , book_name         VARCHAR(100)    NOT NULL COMMENT 'e.g. Genesis, Al-Fatihah'
    , chapter_number    INT UNSIGNED    NULL     COMMENT 'NULL if comment is on whole book'
    , verse_number      INT UNSIGNED    NULL     COMMENT 'NULL if comment is on whole chapter'
    , content           TEXT            NOT NULL
    , tags              VARCHAR(500)    NULL     COMMENT 'Comma separated user tags'
    , created_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    , updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                 ON UPDATE CURRENT_TIMESTAMP(3)
    , CONSTRAINT pk_user_comment PRIMARY KEY (id)
    , INDEX idx_comment_source  (text_source_id)
    , INDEX idx_comment_ref     (text_source_id, book_name, chapter_number, verse_number)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;

-- ── Reader Session State ─────────────────────────────────────────────
-- Persists the user's last reading position and column layout
CREATE TABLE IF NOT EXISTS reader_session (
     id                 CHAR(36)        NOT NULL DEFAULT (UUID())
    , session_key       VARCHAR(100)    NOT NULL COMMENT 'Browser session or user identifier'
    , column_layout     JSON            NOT NULL COMMENT 'Ordered list of open source columns'
    , display_options   JSON            NOT NULL COMMENT 'caps, showChapters, showVerses, orderMode'
    , last_book         VARCHAR(100)    NULL
    , last_chapter      INT UNSIGNED    NULL
    , last_verse        INT UNSIGNED    NULL
    , updated_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                                 ON UPDATE CURRENT_TIMESTAMP(3)
    , CONSTRAINT pk_reader_session      PRIMARY KEY (id)
    , CONSTRAINT uq_reader_session_key  UNIQUE (session_key)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;

-- ── Ingestion Job Log ────────────────────────────────────────────────
-- Audit trail of every ingestion run
CREATE TABLE IF NOT EXISTS ingestion_job_log (
     id                 CHAR(36)        NOT NULL DEFAULT (UUID())
    , source_url        VARCHAR(2000)   NOT NULL
    , source_type       VARCHAR(50)     NOT NULL COMMENT 'BIBLE, QURAN, TORAH, COMMENTARY'
    , format_detected   VARCHAR(50)     NULL     COMMENT 'OSIS, TEI, ZEFANIA, PLAIN'
    , document_id       VARCHAR(100)    NULL     COMMENT 'eXist-db document id on success'
    , status            VARCHAR(20)     NOT NULL COMMENT 'RUNNING, SUCCESS, FAILED'
    , verses_ingested   INT UNSIGNED    NULL
    , error_message     TEXT            NULL
    , started_at        DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    , completed_at      DATETIME(3)     NULL
    , CONSTRAINT pk_ingestion_job_log   PRIMARY KEY (id)
    , INDEX idx_job_status              (status)
    , INDEX idx_job_document            (document_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;

-- ── Source Metadata ──────────────────────────────────────────────────
-- Registry of all ingested texts with their eXist-db document ids
CREATE TABLE IF NOT EXISTS source_metadata (
     id                 CHAR(36)        NOT NULL DEFAULT (UUID())
    , document_id       VARCHAR(100)    NOT NULL COMMENT 'eXist-db document id'
    , text_type         VARCHAR(20)     NOT NULL COMMENT 'BIBLE, QURAN, TORAH, COMMENTARY'
    , translation       VARCHAR(200)    NOT NULL
    , abbreviation      VARCHAR(20)     NOT NULL
    , language_bcp47    VARCHAR(20)     NOT NULL
    , language_iso639_3 CHAR(3)         NOT NULL
    , direction         CHAR(3)         NOT NULL COMMENT 'ltr or rtl'
    , year_published    INT             NULL
    , license           VARCHAR(200)    NULL
    , total_verses      INT UNSIGNED    NULL
    , ingested_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    , CONSTRAINT pk_source_metadata     PRIMARY KEY (id)
    , CONSTRAINT uq_document_id         UNIQUE (document_id)
    , INDEX idx_source_type             (text_type)
    , INDEX idx_source_language         (language_bcp47)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;
