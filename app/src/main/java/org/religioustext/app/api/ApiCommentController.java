// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import jakarta.servlet.http.HttpServletRequest;
import org.religioustext.app.api.ApiTextController.Span;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.CommentAuthorService;
import org.religioustext.app.service.CommentQueryService;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.PersonalNoteService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/comments} and {@code GET /api/v1/channels} — what has been said about a
 * passage, with no scripture attached (docs/api-spec.md §3.6).
 *
 * <p>Three kinds of comment, chosen with {@code kinds}:
 * <ul>
 *   <li>{@code channel} — public, approved comments: the arguments imported from channels
 *       (attributed to the channel, with the video link) and what members published;</li>
 *   <li>{@code translator} — the translator's own notes of ONE edition, so {@code src} is
 *       required, and the edition's licence gates them exactly as it gates its text;</li>
 *   <li>{@code personal} — the key owner's own comments (drafts included) and private study
 *       notes. Nobody else's are ever reachable: the lookup is bound to the account behind
 *       the key, and such a response is never cacheable.</li>
 * </ul>
 *
 * <p>One comment appears once, whatever number of the asked-for verses it cites, and carries
 * its whole reference list — including verses outside the request.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@RestController
@RequestMapping("/api/v1")
public class ApiCommentController {

    /** References per call, as for {@code /passages}. */
    static final int MAX_REFS = ApiTextController.MAX_REFS;

    /** Comments per response; beyond it the payload says {@code truncated}. */
    static final int MAX_COMMENTS = 200;

    static final String KIND_CHANNEL    = "channel";
    static final String KIND_TRANSLATOR = "translator";
    static final String KIND_PERSONAL   = "personal";

    private static final String PERMALINK = "https://common-root.org/reader?comment=";

    private final CommentQueryService  comments;
    private final CommentAuthorService authoring;
    private final PersonalNoteService  notes;
    private final AppReadPolicy reads;
    private final ApiCorpusService    corpus;

    /**
     * @param aCommentQueryService the comment read side
     * @param aCommentAuthorService knows which of the owner's comments were dictated
     * @param aPersonalNoteService the key owner's private notes
     * @param anApiCorpusService   resolves {@code src} to an edition
     */
    public ApiCommentController(final CommentQueryService aCommentQueryService,
                                final CommentAuthorService aCommentAuthorService,
                                final PersonalNoteService aPersonalNoteService,
                                final ApiCorpusService anApiCorpusService,
                                final AppReadPolicy anAppReadPolicy) {
        this.reads    = anAppReadPolicy;
        this.comments = aCommentQueryService;
        this.authoring = aCommentAuthorService;
        this.notes    = aPersonalNoteService;
        this.corpus   = anApiCorpusService;
    }

    /**
     * {@code GET /api/v1/comments?ref=JHN.3.16&kinds=channel,translator,personal&src=lut1912}.
     *
     * @param aRef     comma-separated link-format references, each within one chapter; at most
     *                 {@value #MAX_REFS}
     * @param theKinds comma-separated kinds; absent means {@code channel}, plus
     *                 {@code translator} when {@code src} is given
     * @param aSrc     the edition whose translator notes are wanted; required for
     *                 {@code translator}, ignored by the other kinds
     * @param aView    {@code counts} answers how many public comments each verse has instead of the
     *                 comments themselves — a chapter of John can have far more than
     *                 {@value #MAX_COMMENTS}, and a reader marking its verses needs every count;
     *                 absent means the comments
     * @param aRequest carries the resolved key, which names the account for {@code personal}
     * @return the comments (or the counts), or an {@link ApiError}
     */
    @GetMapping(value = "/comments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> comments(@RequestParam("ref") final String aRef,
                                           @RequestParam(value = "kinds", required = false) final String theKinds,
                                           @RequestParam(value = "src", required = false) final String aSrc,
                                           @RequestParam(value = "view", required = false) final String aView,
                                           final HttpServletRequest aRequest) {
        final boolean countsOnly = "counts".equals(aView);
        if (aView != null && !countsOnly) return error(400, "bad_view", "view is 'counts' or absent.");
        final boolean hasSrc = aSrc != null && !aSrc.isBlank();

        final Set<String> kinds = new LinkedHashSet<>();
        if (theKinds == null || theKinds.isBlank()) {
            kinds.add(KIND_CHANNEL);
            if (hasSrc) kinds.add(KIND_TRANSLATOR);
        } else {
            for (final String raw : theKinds.split(",")) {
                final String k = raw.trim().toLowerCase(Locale.ROOT);
                if (k.isEmpty()) continue;
                if (!k.equals(KIND_CHANNEL) && !k.equals(KIND_TRANSLATOR) && !k.equals(KIND_PERSONAL))
                    return error(400, "bad_kind", "Unknown kind '" + raw.trim()
                        + "'. Use channel, translator, personal.");
                kinds.add(k);
            }
            if (kinds.isEmpty()) return error(400, "bad_kind", "kinds names no kind.");
        }

        SourceRow row = null;
        if (kinds.contains(KIND_TRANSLATOR)) {
            if (!hasSrc) return error(400, "src_required",
                "Translator notes belong to one edition: add src=<token> (see /api/v1/texts).");
            row = corpus.resolve(aSrc.trim());
            if (row == null) return error(404, "unknown_source",
                "No edition '" + aSrc + "'. See /api/v1/texts for tokens.");
            // translator notes are the edition's own words, read like its text
            final AppReadPolicy.Access access = reads.access(row, aRequest);
            if (access == AppReadPolicy.Access.REFUSED || access == AppReadPolicy.Access.SPENT) {
                return reads.refusal(row, access);
            }
        }

        String email = null;
        if (kinds.contains(KIND_PERSONAL) && !countsOnly) {
            final ResolvedKey key = (ResolvedKey) aRequest.getAttribute(ApiAuthFilter.ATTR_KEY);
            email = key == null || key.owner() == null ? null : key.owner().getEmail();
            if (email == null || email.isBlank()) return error(401, "missing_key",
                "Personal comments need the key of the account they belong to.");
        }

        final List<Span> spans = new ArrayList<>();
        for (final String raw : aRef.split(",")) {
            if (raw.isBlank()) continue;
            final Span span = Span.of(raw);
            if (span == null) return error(400, "bad_ref", "Unrecognised reference '" + raw.trim()
                + "'. Use link-format: JHN.1, JHN.1.1, PHP.4.10-13, Q.2.255.");
            if (span.crossesChapters()) return error(400, "bad_ref",
                "Ranges must stay within one chapter in v1 (" + raw.trim() + ").");
            spans.add(span);
        }
        if (spans.isEmpty()) return error(400, "bad_ref", "ref names no reference.");
        if (spans.size() > MAX_REFS) return error(400, "too_many_refs",
            "At most " + MAX_REFS + " references per call; got " + spans.size() + ".");

        if (countsOnly) return counts(spans, kinds, row);

        // One comment, one appearance: keyed by its public id, first sighting wins. Personal
        // goes first so the owner's published comment is reported as theirs, with its state.
        final Map<String, Map<String, Object>> seen = new LinkedHashMap<>();
        final List<Map<String, Object>> personalNotes = new ArrayList<>();
        final List<String> refs = new ArrayList<>();
        for (final Span span : spans) {
            refs.add(span.format());
            if (email != null) {
                collect(seen, span, KIND_PERSONAL, comments.ownForChapter(email, span.bookCode(), span.chapter()));
                notes.detailedForChapter(email, span.bookCode(), span.chapter()).entrySet().stream()
                    .filter(e -> within(span, e.getKey()))
                    .sorted(Comparator.comparingInt(e -> verseNumber(e.getKey())))
                    .forEach(e -> {
                        final Map<String, Object> n = new LinkedHashMap<>();
                        n.put("ref", verseRef(span, verseNumber(e.getKey())));
                        n.put("body", e.getValue().content());
                        n.put("dictated", e.getValue().dictated());
                        personalNotes.add(n);
                    });
            }
            if (kinds.contains(KIND_CHANNEL))
                collect(seen, span, KIND_CHANNEL, comments.forChapter(span.bookCode(), span.chapter(), null));
            if (row != null)
                collect(seen, span, KIND_TRANSLATOR,
                    comments.translatorNotesForChapter(span.bookCode(), span.chapter(), row.id()));
        }

        final List<Map<String, Object>> out = new ArrayList<>(seen.values());
        if (email != null) {
            // The dictated marker is the author's own business: on their comments only.
            final List<String> mine = new ArrayList<>();
            for (final Map<String, Object> c : out)
                if (KIND_PERSONAL.equals(c.get("kind")) && c.get("id") != null) mine.add((String) c.get("id"));
            final Set<String> dictated = authoring.dictatedAmong(email, mine);
            for (final Map<String, Object> c : out)
                if (KIND_PERSONAL.equals(c.get("kind"))) c.put("dictated", dictated.contains(c.get("id")));
        }
        final boolean truncated = out.size() > MAX_COMMENTS;

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("refs", refs);
        body.put("kinds", kinds);
        if (row != null) body.put("text", ApiTextController.token(row));
        body.put("comments", truncated ? out.subList(0, MAX_COMMENTS) : out);
        if (email != null) body.put("notes", personalNotes);
        body.put("truncated", truncated);
        // Anything personal is the account's alone; public comments move between deploys.
        final CacheControl cache = email != null
            ? CacheControl.noStore().cachePrivate()
            : row != null ? AppReadPolicy.cacheFor(row, 1, TimeUnit.MINUTES)
            : CacheControl.maxAge(1, TimeUnit.MINUTES).cachePublic();
        return ResponseEntity.ok().cacheControl(cache).body(body);
    }

    /**
     * {@code GET /api/v1/channels} — every voice that currently has a public comment: the
     * channels first, then members who publish under a display name. The values are what
     * {@code channel} carries in {@code /comments}, so a client can offer them as a filter.
     *
     * @return the voices, sorted
     */
    @GetMapping(value = "/channels", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> channels() {
        final List<String> voices = comments.knownVoices();
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", voices.size());
        body.put("channels", voices);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    // ── Shapes ─────────────────────────────────────────────────────────────

    /** Adds the span's comments, verse order, skipping any already present. */
    /**
     * The {@code view=counts} answer: per verse, how many public comments (channel, plus translator
     * notes when {@code src} names an edition) touch it. A comment citing several verses counts on
     * each. Personal comments are left out: a reader's own published ones are already among the
     * channel comments, and their private ones are theirs to list, not to badge.
     *
     * @param theSpans the requested references
     * @param theKinds the requested kinds; {@code personal} is ignored here
     * @param aRow     the edition for translator notes, or {@code null}
     * @return 200 with {@code refs} and {@code counts} keyed by link-format verse reference
     */
    private ResponseEntity<Object> counts(final List<Span> theSpans, final Set<String> theKinds,
                                          final SourceRow aRow) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final List<String> refs = new ArrayList<>();
        for (final Span span : theSpans) {
            refs.add(span.format());
            final List<Map<String, List<VerseComment>>> chapters = new ArrayList<>();
            if (theKinds.contains(KIND_CHANNEL))
                chapters.add(comments.forChapter(span.bookCode(), span.chapter(), null));
            if (aRow != null)
                chapters.add(comments.translatorNotesForChapter(span.bookCode(), span.chapter(), aRow.id()));
            for (final Map<String, List<VerseComment>> chapter : chapters) {
                chapter.entrySet().stream()
                    .filter(e -> within(span, e.getKey()) && !e.getValue().isEmpty())
                    .sorted(Comparator.comparingInt(e -> verseNumber(e.getKey())))
                    .forEach(e -> counts.merge(verseRef(span, verseNumber(e.getKey())),
                                               e.getValue().size(), Integer::sum));
            }
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("refs", refs);
        body.put("counts", counts);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.MINUTES).cachePublic()).body(body);
    }

    private static void collect(final Map<String, Map<String, Object>> theSeen, final Span aSpan,
                                final String aKind, final Map<String, List<VerseComment>> theChapter) {
        theChapter.entrySet().stream()
            .filter(e -> within(aSpan, e.getKey()))
            .sorted(Comparator.comparingInt(e -> verseNumber(e.getKey())))
            .forEach(e -> {
                int n = 0;
                for (final VerseComment c : e.getValue()) {
                    // Pre-scheme rows have no public id; the verse and position keep them distinct.
                    final String key = c.publicId() != null ? c.publicId()
                        : aKind + "|" + aSpan.bookCode() + "|" + aSpan.chapter() + "|" + e.getKey() + "|" + n;
                    n++;
                    theSeen.putIfAbsent(key, shape(c, aKind));
                }
            });
    }

    static Map<String, Object> shape(final VerseComment aComment, final String aKind) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", aComment.publicId());
        m.put("kind", aKind);
        if (aComment.publicId() != null) m.put("url", PERMALINK + aComment.publicId());
        if (!KIND_TRANSLATOR.equals(aKind)) {
            m.put("channel", CommentQueryService.voiceOf(aComment));
            // The timecoded link to where this verse is discussed, else the video itself.
            final String video = aComment.videoUrl() != null && !aComment.videoUrl().isBlank()
                ? aComment.videoUrl() : aComment.watchUrl();
            if (video != null) m.put("video", video);
            if (aComment.watchTitle() != null) m.put("videoTitle", aComment.watchTitle());
        }
        final List<String> refs = new ArrayList<>();
        for (final VerseComment.Ref r : aComment.refs()) {
            refs.add(r.quran() ? "Q." + r.bookCode() + "." + r.verse()
                               : r.bookCode() + "." + r.chapter() + "." + r.verse());
        }
        m.put("refs", refs);
        if (KIND_PERSONAL.equals(aKind)) m.put("state", aComment.unpublished() ? "private" : "published");
        m.put("body", aComment.content());
        return m;
    }

    private static boolean within(final Span aSpan, final String aVerse) {
        final int v = verseNumber(aVerse);
        return v > 0 && (aSpan.from() <= 0 || (v >= aSpan.from() && v <= aSpan.to()));
    }

    private static int verseNumber(final String aVerse) {
        try { return Integer.parseInt(aVerse.trim()); } catch (final NumberFormatException e) { return -1; }
    }

    private static String verseRef(final Span aSpan, final int aVerse) {
        return aSpan.ref().quran() ? "Q." + aSpan.bookCode() + "." + aVerse
                                   : aSpan.bookCode() + "." + aSpan.chapter() + "." + aVerse;
    }

    private static ResponseEntity<Object> error(final int aStatus, final String aSlug, final String aMessage) {
        return ResponseEntity.status(aStatus).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(aStatus, aSlug, aMessage, "comments"));
    }
}
