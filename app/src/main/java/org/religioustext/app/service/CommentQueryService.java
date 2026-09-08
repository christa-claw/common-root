// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.CommentReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read side of the comments feature: which public comments cite the verses of a
 * given (bookCode, chapter), shaped for the reader.
 *
 * Anchoring follows the stored reference vocabulary (session 14): a Bible verse
 * is (USFM code, chapter, verse); a Qur'an ayah is (surah number as the book
 * code, chapter 1, ayah) — the same shape the reader renders, so the rendered
 * verse's own bookCode/chapter/number are the lookup key directly.
 *
 * DTOs are fully materialized inside the transaction (the entity graph is lazy),
 * so the UI never touches a detached proxy.
 */
@Service
public class CommentQueryService {

    /** One comment as shown under one specific verse. publicId is the stable
     *  permalink id (?comment=cmt_…), null only for pre-scheme rows; videoUrl is
     *  the timecoded "play where THIS verse is discussed" link (null when none
     *  was resolved); watchUrl/watchLabel/watchTitle come from the comment's
     *  external video reference; refs lists every verse the comment cites (for
     *  cross-links). {@code own} marks the caller's own comment (from the
     *  own-overlay fetch), {@code unpublished} a private draft only its author
     *  sees — both false on the public read path. */
    public record VerseComment(String publicId, String content, String videoUrl,
                               String watchUrl, String watchLabel, String watchTitle,
                               List<Ref> refs, boolean own, boolean unpublished,
                               String author) {
        public record Ref(boolean quran, String bookCode, int chapter, int verse) {}
    }

    // ── Voices (V17) ──────────────────────────────────────────────────────
    //
    // A comment's VOICE is whom it speaks as, and it is the unit a reader mutes.
    // Three sources, in falling order of authority:
    //   1. the external reference's label  — an imported argument's channel;
    //   2. a leading "[Channel] " / "[Channel] — " in the content, which is how
    //      older seeded arguments carried it before labels existed;
    //   3. the author account's display name, for a comment a person wrote.
    // Null means unattributable — an anonymous comment from an account with no
    // display name. Such a comment cannot be muted individually BY DESIGN: there
    // is no stable, non-identifying handle to mute it by, and inventing one from
    // the email would leak an address the reader never chose to publish.

    /** The voice a comment speaks as, or null when it is unattributable. */
    public static String voiceOf(final VerseComment aComment) {
        if (aComment == null) return null;
        if (aComment.watchLabel() != null && !aComment.watchLabel().isBlank())
            return aComment.watchLabel().trim();
        final String txt = aComment.content();
        if (txt != null && txt.startsWith("[")) {
            final int close = txt.indexOf(']');
            final int dash  = txt.indexOf(" \u2014 ");
            final int end = (dash > 0 && (close < 0 || dash < close)) ? dash : close;
            if (end > 1) return txt.substring(1, end).trim();
        }
        return aComment.author() == null || aComment.author().isBlank()
            ? null : aComment.author().trim();
    }

    /** The stored newline-separated mute list as a set. Never null. */
    public static Set<String> parseMuted(final String aStored) {
        if (aStored == null || aStored.isBlank()) return Set.of();
        final Set<String> out = new LinkedHashSet<>();
        for (final String line : aStored.split("\n")) {
            final String v = line.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** A mute set back to its stored form. Blank/empty stores as null. */
    public static String formatMuted(final Collection<String> theVoices) {
        if (theVoices == null || theVoices.isEmpty()) return null;
        final String joined = theVoices.stream()
            .filter(v -> v != null && !v.isBlank()).map(String::trim)
            .distinct().collect(Collectors.joining("\n"));
        return joined.isBlank() ? null : joined;
    }

    /** Whether this comment should be hidden from this reader.
     *
     *  <p>Holds the two invariants the mute list must never break: a reader's
     *  OWN comments are always visible (muting a voice you also write as would
     *  hide your own drafts, which reads as data loss), and an unattributable
     *  comment cannot be muted because it has no voice to match. */
    public static boolean isMuted(final VerseComment aComment, final Set<String> theMuted) {
        if (aComment == null || theMuted == null || theMuted.isEmpty()) return false;
        if (aComment.own()) return false;
        final String voice = voiceOf(aComment);
        return voice != null && theMuted.contains(voice);
    }

    /** Every voice currently present in public, approved comments — the pick list
     *  for the preferences page. Labels first (channels), then author display
     *  names, de-duplicated and sorted; a reader can only mute what exists. */
    @Transactional(readOnly = true)
    public List<String> knownVoices() {
        final Set<String> out = new LinkedHashSet<>();
        out.addAll(em.createQuery(
                "SELECT DISTINCT r.label FROM CommentReference r "
              + "WHERE r.refType = :rt AND r.label IS NOT NULL "
              + "AND r.comment.isPublic = true AND r.comment.moderationStatus = :ms",
                String.class)
            .setParameter("rt", CommentReference.RefType.external)
            .setParameter("ms", Comment.ModerationStatus.approved)
            .getResultList());
        out.addAll(em.createQuery(
                "SELECT DISTINCT c.user.displayName FROM Comment c "
              + "WHERE c.isPublic = true AND c.moderationStatus = :ms "
              + "AND c.user.displayName IS NOT NULL AND c.user.system = false",
                String.class)
            .setParameter("ms", Comment.ModerationStatus.approved)
            .getResultList());
        return out.stream().filter(v -> v != null && !v.isBlank())
            .map(String::trim).distinct().sorted().toList();
    }

    @PersistenceContext
    private EntityManager em;

    /** Public, approved comments citing verses of (bookCode, chapter), keyed by
     *  verse number (as a string, matching VerseRef.getVerseNumber()).
     *
     *  <p>{@code aSourceId} is the corpus source id of the COLUMN being rendered
     *  (e.g. "bible-lut1912-1912"). It exists for translator notes: a comment
     *  seeded from a {@code notes-%} ledger (V16) is edition-BOUND and renders
     *  only when its reference's sourceId matches the column — a 1912 gloss
     *  belongs under the 1912 text it annotates, not under the KJV's rendering
     *  of the same verse. Every other comment (user-authored, arguments) keeps
     *  the V5 rule: sourceId is provenance only, display is edition-independent,
     *  and the filter below leaves them untouched. Null = unknown column source,
     *  which shows no edition-bound notes at all (never the wrong edition's). */
    @Transactional(readOnly = true)
    public Map<String, List<VerseComment>> forChapter(final String aBookCode, final int aChapter,
                                                      final String aSourceId) {
        if (aBookCode == null || aBookCode.isBlank()) return Map.of();
        final List<CommentReference> rows = em.createQuery(
                "SELECT r FROM CommentReference r JOIN FETCH r.comment c LEFT JOIN FETCH c.user "
              + "WHERE r.refType = :rt AND r.bookCode = :code AND r.chapter = :ch "
              + "AND c.isPublic = true AND c.moderationStatus = :ms "
              // Edition-bound notes: only under their own edition's column.
              + "AND (c.seedLedger IS NULL OR c.seedLedger NOT LIKE 'notes-%' "
              + "     OR r.sourceId = :src) "
              + "ORDER BY c.createdAt", CommentReference.class)
            .setParameter("rt", CommentReference.RefType.internal)
            .setParameter("code", aBookCode)
            .setParameter("ch", aChapter)
            .setParameter("ms", Comment.ModerationStatus.approved)
            .setParameter("src", aSourceId)
            .getResultList();
        if (rows.isEmpty()) return Map.of();

        final Map<String, List<VerseComment>> byVerse = new HashMap<>();
        for (final CommentReference row : rows) {
            if (row.getVerse() == null) continue;
            final Comment c = row.getComment();

            String watchUrl = null, watchLabel = null, watchTitle = null;
            final List<VerseComment.Ref> refs = new ArrayList<>();
            for (final CommentReference r : c.getReferences()) {   // lazy — inside tx
                if (r.getRefType() == CommentReference.RefType.external) {
                    if (watchUrl == null) {
                        watchUrl   = r.getUrl();
                        watchLabel = r.getLabel();
                        watchTitle = r.getDescription();
                    }
                } else if (r.getBookCode() != null && r.getChapter() != null && r.getVerse() != null) {
                    refs.add(new VerseComment.Ref(
                        isSurahCode(r.getBookCode()), r.getBookCode(), r.getChapter(), r.getVerse()));
                }
            }

            byVerse.computeIfAbsent(String.valueOf(row.getVerse()), k -> new ArrayList<>())
                .add(new VerseComment(c.getPublicId(), c.getContent(), row.getVideoUrl(),
                                      watchUrl, watchLabel, watchTitle, refs, false, false,
                                      authorOf(c)));
        }
        return byVerse;
    }

    /**
     * The CALLER'S OWN comments citing verses of (bookCode, chapter) — INCLUDING private
     * drafts and pending/rejected ones — keyed by verse number. The reading view overlays
     * these on the public map so an author always sees their own annotations under every
     * verse they cite (a private multi-verse draft is the "personal note that points to
     * multiple verses"). Only ever shown to the author: the query is bound to their email.
     *
     * @param anEmail   the signed-in author's email ({@code null}/blank → empty map)
     * @param aBookCode the USFM book code (or surah number)
     * @param aChapter  the chapter number
     * @return the author's comments per verse, flagged {@code own} (+ {@code unpublished}
     *         when not publicly visible)
     */
    @Transactional(readOnly = true)
    public Map<String, List<VerseComment>> ownForChapter(final String anEmail,
                                                         final String aBookCode, final int aChapter) {
        if (anEmail == null || anEmail.isBlank() || aBookCode == null || aBookCode.isBlank())
            return Map.of();
        final List<CommentReference> rows = em.createQuery(
                "SELECT r FROM CommentReference r JOIN FETCH r.comment c LEFT JOIN FETCH c.user "
              + "WHERE r.refType = :rt AND r.bookCode = :code AND r.chapter = :ch "
              + "AND c.user.email = :email "
              + "ORDER BY c.createdAt", CommentReference.class)
            .setParameter("rt", CommentReference.RefType.internal)
            .setParameter("code", aBookCode)
            .setParameter("ch", aChapter)
            .setParameter("email", anEmail)
            .getResultList();
        if (rows.isEmpty()) return Map.of();

        final Map<String, List<VerseComment>> byVerse = new HashMap<>();
        for (final CommentReference row : rows) {
            if (row.getVerse() == null) continue;
            final Comment c = row.getComment();

            String watchUrl = null, watchLabel = null, watchTitle = null;
            final List<VerseComment.Ref> refs = new ArrayList<>();
            for (final CommentReference r : c.getReferences()) {   // lazy — inside tx
                if (r.getRefType() == CommentReference.RefType.external) {
                    if (watchUrl == null) {
                        watchUrl   = r.getUrl();
                        watchLabel = r.getLabel();
                        watchTitle = r.getDescription();
                    }
                } else if (r.getBookCode() != null && r.getChapter() != null && r.getVerse() != null) {
                    refs.add(new VerseComment.Ref(
                        isSurahCode(r.getBookCode()), r.getBookCode(), r.getChapter(), r.getVerse()));
                }
            }

            byVerse.computeIfAbsent(String.valueOf(row.getVerse()), k -> new ArrayList<>())
                .add(new VerseComment(c.getPublicId(), c.getContent(), row.getVideoUrl(),
                                      watchUrl, watchLabel, watchTitle, refs,
                                      true, !c.isVisiblePublicly(), authorOf(c)));
        }
        return byVerse;
    }

    /** Every public, approved comment with all its references — feeds the
     *  comment-driven browser, where the list is the entry point and the refs
     *  drive the text. videoUrl carries the first per-verse timecoded link, so
     *  ▶ plays from where the argument's first cited verse is discussed. */
    @Transactional(readOnly = true)
    public List<VerseComment> listAll() {
        final List<Comment> cs = em.createQuery(
                "SELECT DISTINCT c FROM Comment c LEFT JOIN FETCH c.references LEFT JOIN FETCH c.user "
              + "WHERE c.isPublic = true AND c.moderationStatus = :ms "
              + "ORDER BY c.createdAt", Comment.class)
            .setParameter("ms", Comment.ModerationStatus.approved)
            .getResultList();
        final List<VerseComment> out = new ArrayList<>();
        for (final Comment c : cs) {
            String watchUrl = null, watchLabel = null, watchTitle = null, firstVideo = null;
            final List<VerseComment.Ref> refs = new ArrayList<>();
            for (final CommentReference r : c.getReferences()) {
                if (r.getRefType() == CommentReference.RefType.external) {
                    if (watchUrl == null) {
                        watchUrl   = r.getUrl();
                        watchLabel = r.getLabel();
                        watchTitle = r.getDescription();
                    }
                } else if (r.getBookCode() != null && r.getChapter() != null && r.getVerse() != null) {
                    refs.add(new VerseComment.Ref(
                        isSurahCode(r.getBookCode()), r.getBookCode(), r.getChapter(), r.getVerse()));
                    // Prefer the first TIMECODED link — a plain one only as fallback.
                    final String vu = r.getVideoUrl();
                    if (vu != null && !vu.isBlank()
                            && (firstVideo == null || (!firstVideo.contains("&t=") && vu.contains("&t="))))
                        firstVideo = vu;
                }
            }
            out.add(new VerseComment(c.getPublicId(), c.getContent(), firstVideo,
                                     watchUrl, watchLabel, watchTitle, refs, false, false,
                                     authorOf(c)));
        }
        return out;
    }

    /** The display name to attribute a comment to, or null.
     *
     *  <p>Display name ONLY \u2014 never the email, which is not the reader's to
     *  see. A system account (the seeder's owner for imported arguments)
     *  attributes to nothing: those comments speak as their channel, and an
     *  account name behind them would be an implementation detail leaking onto
     *  the page. An account that has set no display name stays anonymous, and
     *  therefore unmutable. */
    private static String authorOf(final Comment aComment) {
        final var u = aComment.getUser();
        if (u == null || u.isSystem()) return null;
        final String name = u.getDisplayName();
        return name == null || name.isBlank() ? null : name.trim();
    }

    /** Qur'an refs carry the surah NUMBER as the book code; Bible codes are
     *  alphanumeric USFM. */
    private static boolean isSurahCode(final String aBookCode) {
        return aBookCode.chars().allMatch(Character::isDigit);
    }
}
