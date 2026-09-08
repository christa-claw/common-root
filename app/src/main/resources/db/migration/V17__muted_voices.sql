-- Per-user mute list for comment VOICES.
--
-- A "voice" is whom a comment speaks as: an imported argument's channel (the
-- external reference's label, e.g. 'Shamounian Explains') or, for a comment a
-- person wrote, that account's display name. One list covers both, because a
-- reader curating what they read does not care which pipeline produced it.
--
-- A newline-separated list, not a join table, and deliberately so: a voice is a
-- display STRING rather than an entity. Channels are not rows anywhere, and a
-- renamed channel should simply stop matching instead of dangling a foreign key.
-- Newline rather than comma because channel names contain commas and apostrophes
-- ("DAWAH BRO'S PODCAST") but never a line break.
--
-- Muting hides the voice from the inline verse bubbles AND the comments panel —
-- the reader stops encountering it, rather than merely filtering a list. Two
-- invariants live in code, not here: the reader's OWN comments are never muted,
-- and an explicit ?comments=<channel> deep link overrides the mute for that
-- visit (so a channel's outreach link never opens an empty reader).
ALTER TABLE user_preferences
    ADD COLUMN muted_voices TEXT NULL AFTER show_comment_markers;
