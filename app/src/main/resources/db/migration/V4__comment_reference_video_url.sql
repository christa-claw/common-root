-- ============================================================
-- V4__comment_reference_video_url.sql
-- Adds a per-reference video link to comment_references.
--
-- Transcript-derived comments cite verses that are discussed at a
-- specific moment of a YouTube video; the enrichment pass
-- (transcripts/enrich_arguments.py) resolves a timecoded link per
-- verse reference. Stored on the INTERNAL reference rows so the
-- reader can offer "play the video at the point where this verse
-- is discussed" beside each cited verse. NULL for ordinary refs.
-- ============================================================

ALTER TABLE comment_references
    ADD COLUMN video_url VARCHAR(2048) NULL AFTER verse;
