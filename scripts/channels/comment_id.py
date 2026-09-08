# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""Deterministic comment ids for arguments.json entries.

Shared by the Ollama extractor (mints an id when it CREATES an entry) and the
one-time backfill (stamp_argument_ids.py, fills ids for pre-existing entries).
Both import from here so the id logic lives in exactly one place — if the two
ever computed ids differently, the same argument would get two ids and every
shared &comment= link would rot.

Design (decided 2026-07-06):
  * cmt_<uuid5>  — the prefix names the OBJECT KIND (a comment), never the owner.
    Auto-generated arguments, user-authored comments, and adopted arguments all
    share the scheme; ownership is a column, not part of the id.
  * Deterministic: a v5 (namespace) UUID over the argument's intrinsic identity,
    so re-extracting or re-running the backfill yields the SAME id (idempotent).
  * Immutable after mint: the extractor/backfill only assign an id to an entry
    that lacks one. Later edits (e.g. a user adopting + editing an argument) must
    NOT re-derive it — the stored id is the permalink.
"""

import uuid

# Fixed project namespace. NEVER change this value: changing it re-mints every
# id and breaks every shared &comment= link and every stored public_id.
COMMENT_NAMESPACE = uuid.UUID("6f4e2d1c-8a3b-4c5d-9e0f-1a2b3c4d5e6f")

ID_PREFIX = "cmt_"


def _ref_key(verse_refs):
    """Order-independent join of an entry's verse-ref strings (stable key part)."""
    if not verse_refs:
        return ""
    refs = []
    for r in verse_refs:
        if isinstance(r, dict):
            refs.append(r.get("ref")
                        or f"{r.get('code', '')}.{r.get('chapter', '')}.{r.get('verse', '')}")
        else:
            refs.append(str(r))
    return ",".join(sorted(refs))


def mint_comment_id(entry):
    """Return the deterministic cmt_ id for an arguments.json entry.

    Keyed on: the source video (video_id, or source_file if a video never
    resolved), the sorted set of cited verse refs, and the argument text. Long
    and specific enough that distinct arguments practically never collide; two
    entries that DO collide are byte-identical arguments (true duplicates).
    """
    source = entry.get("video_id") or entry.get("source_file") or ""
    refs = _ref_key(entry.get("verse_refs"))
    content = (entry.get("argument_summary") or "").strip()
    key = f"{source}|{refs}|{content}"
    return ID_PREFIX + str(uuid.uuid5(COMMENT_NAMESPACE, key))


def mint_note_id(abbr, ref, index, text):
    """Return the deterministic cmt_ id for a translator note.

    Same namespace, prefix and idempotence contract as mint_comment_id — the id
    names a comment, never its owner, so an edition's note and a reader's
    comment are the same kind of object with different ownership.

    Keyed on the EDITION plus the verse ref plus the note's ordinal within that
    verse plus its text. The edition has to be in the key: the same footnote
    text can appear at the same verse in two editions and they are different
    notes. The ordinal disambiguates two notes in one verse; the text makes a
    re-run after an upstream correction mint a new id rather than silently
    rewriting the old one under its permalink.

    :param abbr:  edition abbreviation, e.g. "LUT1912"
    :param ref:   OSIS-style verse ref, e.g. "Gen.2.14"
    :param index: 0-based ordinal of this note within its verse
    :param text:  the note's own text
    """
    key = f"note|{abbr}|{ref}|{index}|{(text or '').strip()}"
    return ID_PREFIX + str(uuid.uuid5(COMMENT_NAMESPACE, key))
