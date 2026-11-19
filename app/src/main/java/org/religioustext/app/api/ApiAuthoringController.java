// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.religioustext.app.api.ApiTextController.Span;
import org.religioustext.app.model.user.User;
import org.religioustext.app.service.AccessService;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.CommentAuthorService;
import org.religioustext.app.service.CommentAuthorService.Authored;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.PersonalNoteService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The key owner's own writing through the API (docs/api-spec.md §0): their comments
 * ({@code POST}/{@code PUT}/{@code DELETE /api/v1/comments}) and their private study notes
 * ({@code PUT}/{@code DELETE /api/v1/notes/{ref}}).
 *
 * <p>Every route acts as the account behind the key and on that account's own rows only: a
 * comment id that belongs to someone else is a 404, exactly like one that does not exist, so
 * the API never confirms what it will not show. Publishing keeps the site's rule — it needs
 * the contributor role; any account may keep private drafts.
 *
 * <p>Responses are the account's alone and are never cacheable.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@RestController
@RequestMapping("/api/v1")
public class ApiAuthoringController {

    /** The longest comment or note the API accepts, in characters. */
    static final int MAX_BODY_CHARS = 10_000;

    /** The most verses one comment may cite. */
    static final int MAX_VERSES = 50;

    private static final String PERMALINK = "https://common-root.org/reader?comment=";

    private final CommentAuthorService authoring;
    private final PersonalNoteService  notes;
    private final ApiCorpusService     corpus;
    private final AccessService        access;

    /**
     * @param aCommentAuthorService the owner's comment writes
     * @param aPersonalNoteService  the owner's private notes
     * @param anApiCorpusService    resolves {@code src} to an edition
     * @param anAccessService       decides who may publish
     */
    public ApiAuthoringController(final CommentAuthorService aCommentAuthorService,
                                  final PersonalNoteService aPersonalNoteService,
                                  final ApiCorpusService anApiCorpusService,
                                  final AccessService anAccessService) {
        this.authoring = aCommentAuthorService;
        this.notes     = aPersonalNoteService;
        this.corpus    = anApiCorpusService;
        this.access    = anAccessService;
    }

    /**
     * A comment as the client sends it.
     *
     * @param refs    the verses it cites: link-format, single verses or ranges within one chapter
     * @param body    the text
     * @param visible JSON {@code public}: publish ({@code true}) or keep private; on an update,
     *                absent means "leave as it is"
     * @param src      optional edition token, kept as provenance
     * @param dictated whether the text was spoken; on an update, absent means "leave as it is"
     */
    public record CommentIn(List<String> refs, String body,
                            @JsonProperty("public") Boolean visible, String src, Boolean dictated) { }

    /**
     * A note as the client sends it.
     *
     * @param body     the text
     * @param src      optional edition token, kept as provenance
     * @param dictated whether the text was spoken; absent means typed
     */
    public record NoteIn(String body, String src, Boolean dictated) { }

    // ── Comments ───────────────────────────────────────────────────────────

    /**
     * {@code POST /api/v1/comments} — write a comment on one or more verses. Private unless
     * {@code "public": true}.
     *
     * @param aComment the comment
     * @param aRequest carries the resolved key
     * @return 201 with the stored comment, or an {@link ApiError}
     */
    @PostMapping(value = "/comments", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> create(@RequestBody final CommentIn aComment,
                                         final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        if (aComment == null) return error(400, "bad_request", "Body needs refs[] and body.", "comments");
        final ResponseEntity<Object> badBody = checkBody(aComment.body(), "comments");
        if (badBody != null) return badBody;

        if (aComment.refs() == null || aComment.refs().isEmpty())
            return error(400, "bad_ref", "refs must name at least one verse.", "comments");
        final List<VerseComment.Ref> refs = new ArrayList<>();
        for (final String raw : aComment.refs()) {
            final Span span = raw == null ? null : Span.of(raw);
            if (span == null || span.from() <= 0 || span.crossesChapters())
                return error(400, "bad_ref", "'" + raw + "' is not a verse or a range within one "
                    + "chapter. Use link-format: JHN.3.16, PHP.4.10-13, Q.2.255.", "comments");
            for (int v = span.from(); v <= span.to(); v++) {
                refs.add(new VerseComment.Ref(span.ref().quran(), span.bookCode(), span.chapter(), v));
                if (refs.size() > MAX_VERSES) return error(400, "too_many_refs",
                    "A comment may cite at most " + MAX_VERSES + " verses.", "comments");
            }
        }

        final boolean publish = Boolean.TRUE.equals(aComment.visible());
        if (publish && !access.canPublish(owner)) return cannotPublish();
        final Source src = source(aComment.src());
        if (src.refusal != null) return src.refusal;

        final Authored made = authoring.createOwn(owner.getEmail(), src.id, refs, aComment.body(), publish,
                                                    Boolean.TRUE.equals(aComment.dictated()));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore().cachePrivate()).body(shape(made));
    }

    /**
     * {@code PUT /api/v1/comments/{id}} — change the text of one's own comment, publish it or
     * take it private. An absent field is left as it is.
     *
     * @param anId     the comment's {@code cmt_…} id
     * @param aComment the change; {@code refs} and {@code src} are ignored
     * @param aRequest carries the resolved key
     * @return the stored comment, 404 when the caller has none with this id, or an {@link ApiError}
     */
    @PutMapping(value = "/comments/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> update(@PathVariable("id") final String anId,
                                         @RequestBody final CommentIn aComment,
                                         final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        if (aComment == null
                || (aComment.body() == null && aComment.visible() == null && aComment.dictated() == null))
            return error(400, "bad_request", "Body needs at least one of body, public, dictated.", "comments");
        if (aComment.body() != null) {
            final ResponseEntity<Object> badBody = checkBody(aComment.body(), "comments");
            if (badBody != null) return badBody;
        }
        if (Boolean.TRUE.equals(aComment.visible()) && !access.canPublish(owner)) return cannotPublish();

        final Authored stored = authoring.updateOwn(owner.getEmail(), anId, aComment.body(), aComment.visible(),
                                                    aComment.dictated())
            .orElse(null);
        if (stored == null) return noSuchComment(anId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(shape(stored));
    }

    /**
     * {@code DELETE /api/v1/comments/{id}} — delete one's own comment.
     *
     * @param anId     the comment's {@code cmt_…} id
     * @param aRequest carries the resolved key
     * @return 204, or 404 when the caller has none with this id
     */
    @DeleteMapping("/comments/{id}")
    public ResponseEntity<Object> delete(@PathVariable("id") final String anId,
                                         final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        if (!authoring.deleteOwn(owner.getEmail(), anId)) return noSuchComment(anId);
        return ResponseEntity.noContent().build();
    }

    // ── Notes ──────────────────────────────────────────────────────────────

    /**
     * {@code PUT /api/v1/notes/{ref}} — write or replace one's private note on a verse. There
     * is one note per verse, whatever edition is open.
     *
     * @param aRef     a single verse in link-format
     * @param aNote    the note
     * @param aRequest carries the resolved key
     * @return the stored note, or an {@link ApiError}
     */
    @PutMapping(value = "/notes/{ref}", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> putNote(@PathVariable("ref") final String aRef,
                                          @RequestBody final NoteIn aNote,
                                          final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        final Span verse = singleVerse(aRef);
        if (verse == null) return badVerse(aRef);
        final ResponseEntity<Object> badBody = checkBody(aNote == null ? null : aNote.body(), "notes");
        if (badBody != null) return badBody;
        final Source src = source(aNote.src());
        if (src.refusal != null) return src.refusal;

        // PUT replaces the note, marker included: text typed over a dictated note is typed.
        final boolean dictated = Boolean.TRUE.equals(aNote.dictated());
        notes.save(owner.getEmail(), src.id, verse.bookCode(), verse.chapter(), verse.from(), aNote.body(),
                   dictated);
        final Map<String, Object> body = new LinkedHashMap<>();
        // Canonical spelling, whatever case the path used.
        body.put("ref", verse.ref().quran() ? "Q." + verse.bookCode() + "." + verse.from()
                                            : verse.bookCode() + "." + verse.chapter() + "." + verse.from());
        body.put("body", aNote.body().strip());
        body.put("dictated", dictated);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }

    /**
     * {@code DELETE /api/v1/notes/{ref}} — remove one's note on a verse. Idempotent: deleting a
     * note that is not there is still 204.
     *
     * @param aRef     a single verse in link-format
     * @param aRequest carries the resolved key
     * @return 204, or an {@link ApiError} for a reference that is not one verse
     */
    @DeleteMapping("/notes/{ref}")
    public ResponseEntity<Object> deleteNote(@PathVariable("ref") final String aRef,
                                             final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        final Span verse = singleVerse(aRef);
        if (verse == null) return badVerse(aRef);
        notes.delete(owner.getEmail(), verse.bookCode(), verse.chapter(), verse.from());
        return ResponseEntity.noContent().build();
    }

    // ── Shapes and refusals ────────────────────────────────────────────────

    static Map<String, Object> shape(final Authored aComment) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", aComment.publicId());
        m.put("kind", ApiCommentController.KIND_PERSONAL);
        m.put("url", PERMALINK + aComment.publicId());
        final List<String> refs = new ArrayList<>();
        for (final VerseComment.Ref r : aComment.refs()) {
            refs.add(r.quran() ? "Q." + r.bookCode() + "." + r.verse()
                               : r.bookCode() + "." + r.chapter() + "." + r.verse());
        }
        m.put("refs", refs);
        m.put("state", aComment.published() ? "published" : "private");
        m.put("dictated", aComment.dictated());
        m.put("body", aComment.content());
        return m;
    }

    /** The account behind the key, or {@code null} when the request carries none. */
    private static User owner(final HttpServletRequest aRequest) {
        final ResolvedKey key = (ResolvedKey) aRequest.getAttribute(ApiAuthFilter.ATTR_KEY);
        final User owner = key == null ? null : key.owner();
        return owner == null || owner.getEmail() == null || owner.getEmail().isBlank() ? null : owner;
    }

    /** The span when it names exactly one verse, else {@code null}. */
    private static Span singleVerse(final String aRef) {
        final Span span = Span.of(aRef);
        return span == null || span.from() <= 0 || span.to() != span.from() || span.crossesChapters()
            ? null : span;
    }

    /** An optional {@code src}: the edition's id, {@code null} when absent, or a refusal. */
    private record Source(String id, ResponseEntity<Object> refusal) { }

    private Source source(final String aSrc) {
        if (aSrc == null || aSrc.isBlank()) return new Source(null, null);
        final SourceRow row = corpus.resolve(aSrc.trim());
        return row == null
            ? new Source(null, error(404, "unknown_source",
                "No edition '" + aSrc + "'. See /api/v1/texts for tokens.", "texts"))
            : new Source(row.id(), null);
    }

    private static ResponseEntity<Object> checkBody(final String aBody, final String anAnchor) {
        if (aBody == null || aBody.isBlank()) return error(400, "empty_body", "body must not be empty.", anAnchor);
        if (aBody.length() > MAX_BODY_CHARS) return error(400, "body_too_long",
            "body may be at most " + MAX_BODY_CHARS + " characters; got " + aBody.length() + ".", anAnchor);
        return null;
    }

    private static ResponseEntity<Object> noKey() {
        return error(401, "missing_key", "Writing needs the key of the account that writes.", "auth");
    }

    private static ResponseEntity<Object> cannotPublish() {
        return error(403, "publish_not_permitted", "Publishing a comment needs the contributor "
            + "role. Without it a comment can still be saved as private.", "comments");
    }

    private static ResponseEntity<Object> noSuchComment(final String anId) {
        return error(404, "no_such_comment", "You have no comment '" + anId + "'.", "comments");
    }

    private static ResponseEntity<Object> badVerse(final String aRef) {
        return error(400, "bad_ref", "'" + aRef + "' is not a single verse. A note belongs to one "
            + "verse: JHN.3.16, Q.2.255.", "notes");
    }

    private static ResponseEntity<Object> error(final int aStatus, final String aSlug,
                                                final String aMessage, final String anAnchor) {
        return ResponseEntity.status(aStatus).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(aStatus, aSlug, aMessage, anAnchor));
    }
}
