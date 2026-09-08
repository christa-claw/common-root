package org.religioustext.app.api;

import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.AttestationService;
import org.religioustext.app.service.AttestationService.Attestation;
import org.religioustext.app.ui.views.ReaderLink;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.religioustext.app.web.CorpusDownloadController;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The scripture reads of the web API (docs/api-spec.md §3.2, §3.4, §3.5):
 * the catalogue, one edition, one chapter (JSON or XML), and scattered
 * references across one edition. Authentication and metering happened in
 * {@link ApiAuthFilter} before any of this runs.
 *
 * <p>Vocabulary is the reader's: {@code {src}} is a source token
 * ({@code kjv}, {@code q-ar}) and {@code {ref}} a link-format reference
 * ({@code JHN.1}, {@code JHN.1.1}, {@code PHP.4.10-13}, {@code Q.2.255}) —
 * one naming scheme across reader links, About cards and API.
 *
 * <p>Formats: {@code ?format=json|xml} (the query parameter wins, {@code Accept}
 * is the fallback, JSON the default). XML is the corpus's own {@code rt:chapter}
 * subtree with this site's reading-order attributes removed — the same bytes
 * you would cut out of the bulk download, one schema, no API dialect. Plain
 * text arrives with the display modes (spec §3.4) and answers 501 until then.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@RestController
@RequestMapping("/api/v1")
public class ApiTextController {

    /** Scattered-reference cap per call (spec §3.5, §10.9). */
    static final int MAX_REFS = 20;

    /** Attestation on every passage response, header form (spec §3.7). */
    static final String ATTEST_HEADER = "X-CommonRoot-Attestation";

    private final ApiCorpusService   corpus;
    private final AttestationService attest;

    public ApiTextController(final ApiCorpusService anApiCorpusService,
                             final AttestationService anAttestationService) {
        this.corpus = anApiCorpusService;
        this.attest = anAttestationService;
    }

    // ── /texts ────────────────────────────────────────────────────────────

    /**
     * {@code GET /api/v1/texts} — every edition with metadata and whether it may
     * be downloaded. {@code downloadable} is computed by the same licence check
     * that gates the download, so the list can never advertise what the gate
     * then refuses.
     *
     * @return the catalogue as JSON
     */
    @GetMapping(value = "/texts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> texts() {
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final String[] raw : corpus.catalog().rows()) {
            out.add(describe(SourceRow.of(raw)));
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("count", out.size());
        body.put("texts", out);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    /**
     * {@code GET /api/v1/texts/{src}} — one edition's metadata.
     *
     * @param aSrc the source token or document id
     * @return the edition, or 404 with a pointer to the catalogue
     */
    @GetMapping(value = "/texts/{src}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> text(@PathVariable("src") final String aSrc) {
        final SourceRow row = corpus.resolve(aSrc);
        if (row == null) return unknownSource(aSrc);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(describe(row));
    }

    /**
     * {@code GET /api/v1/texts/{src}/books} — the edition's table of contents:
     * every book (surah, hadith book) with its code, names and the chapter
     * numbers actually present, so a consumer can form references that exist
     * instead of guessing. A literal segment, so it wins over {@code {ref}}.
     *
     * @param aSrc the source token or document id
     * @return the books in canonical order
     */
    @GetMapping(value = "/texts/{src}/books", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> books(@PathVariable("src") final String aSrc) {
        final SourceRow row = corpus.resolve(aSrc);
        if (row == null) return unknownSource(aSrc);
        final List<Map<String, Object>> books = new ArrayList<>();
        for (final ApiCorpusService.Book b : corpus.books(row.id())) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", b.code());
            m.put("name", b.name());
            if (b.nativeName() != null) m.put("nativeName", b.nativeName());
            m.put("chapters", b.chapters());
            books.add(m);
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", token(row));
        body.put("type", row.type());
        body.put("count", books.size());
        body.put("books", books);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    // ── /texts/{src}/{ref} ─────────────────────────────────────────────────

    /**
     * {@code GET /api/v1/texts/{src}/{ref}} — a chapter, or the verse span the
     * reference names within it.
     *
     * @param aSrc    the source token or document id
     * @param aRef    a link-format reference within one chapter
     * @param aFormat {@code json} (default), {@code xml}, or {@code text} (501 for now)
     * @param anAccept the Accept header, honoured when {@code format} is absent
     * @return the passage in the requested format
     */
    @GetMapping("/texts/{src}/{ref}")
    public ResponseEntity<Object> chapter(@PathVariable("src") final String aSrc,
                                          @PathVariable("ref") final String aRef,
                                          @RequestParam(value = "format", required = false) final String aFormat,
                                          @RequestHeader(value = "Accept", required = false) final String anAccept) {
        final SourceRow row = corpus.resolve(aSrc);
        if (row == null) return unknownSource(aSrc);
        if (!redistributable(row)) return notRedistributable(row);

        final String format = negotiate(aFormat, anAccept);
        if ("text".equals(format)) return notYet("Plain text arrives with the reader's display modes (spec §3.4).");
        if (format == null) return badRequest("bad_format", "format must be json or xml.", "formats");

        final Span span = Span.of(aRef);
        if (span == null) return badRequest("bad_ref",
            "Unrecognised reference '" + aRef + "'. Use link-format: JHN.1, JHN.1.1, PHP.4.10-13, Q.2.255.", "refs");
        if (span.crossesChapters()) return badRequest("bad_ref",
            "Ranges must stay within one chapter in v1 (" + aRef + ").", "refs");

        final List<VerseRef> verses = span.select(corpus.chapter(row.id(), span.bookCode, span.chapter));
        if (verses.isEmpty()) return notFound("no_such_passage",
            span.format() + " is not in " + row.abbreviation() + ".", "refs");
        // One tag for the passage whatever the format: the canonical form is the
        // cleaned verse text, not the wire bytes (spec §3.7).
        final Attestation tag = attest.mint(token(row), span.format(), verses);

        if ("xml".equals(format)) {
            final String xml = corpus.chapterXml(row.id(), span.bookCode, span.chapter, span.from, span.to);
            if (xml == null) return notFound("no_such_passage",
                span.format() + " is not in " + row.abbreviation() + ".", "refs");
            final HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8));
            headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic());
            headers.set(ATTEST_HEADER, tag.headerValue());
            // Self-validating: the attestation rides inside the document as a
            // processing instruction, so a saved file still carries its proof.
            return new ResponseEntity<>(tag.processingInstruction() + "\n" + xml, headers, 200);
        }

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", token(row));                       // the edition's permanent address; details at /texts/{src}
        body.put("direction", direction(row));              // JSON always states direction — plain text cannot
        body.putAll(passage(span, verses));
        body.put("attestation", attestationJson(tag));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .header(ATTEST_HEADER, tag.headerValue())
            .body(body);
    }

    // ── /passages ──────────────────────────────────────────────────────────

    /**
     * {@code GET /api/v1/passages?src=kjv&refs=JHN.1.1,ROM.9.5,PSA.23.1-6} —
     * scattered verses, grouped by reference in the order asked, under one
     * attestation. A reference may name its own edition with a prefix —
     * {@code refs=kjv:JHN.3.16,gnv:JHN.3.16,web:JHN.3.16} — so the same passage
     * across translations ("when did this reading appear?") is one verifiable
     * bundle. {@code src} may itself list editions — {@code src=kjv,niv&refs=GEN.1.1,GEN.1.2}
     * expands each unprefixed reference across them, verse-major — and may be
     * omitted when every reference carries a prefix.
     *
     * <p>JSON only: a bag of chapters from different books and editions has no
     * single schema-valid XML parent.
     *
     * @param aSrc    default edition(s), comma-separated; optional when every ref is prefixed
     * @param aRefs   comma-separated {@code [edition:]ref}, at most {@value #MAX_REFS}
     * @param aFormat must be absent or {@code json}
     * @return the passages
     */
    @GetMapping(value = "/passages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> passages(@RequestParam(value = "src", required = false) final String aSrc,
                                           @RequestParam("refs") final String aRefs,
                                           @RequestParam(value = "format", required = false) final String aFormat) {
        if (aFormat != null && !"json".equalsIgnoreCase(aFormat)) {
            return badRequest("bad_format", "Scattered passages are JSON only; fetch chapters "
                + "individually for XML.", "formats");
        }
        // src may list several editions: an unprefixed ref expands across all of
        // them, verse-major (GEN.1.1 in each edition, then GEN.1.2 in each), so a
        // comparison reads side by side. A prefixed ref names its own edition.
        final List<SourceRow> defaults = new ArrayList<>();
        if (aSrc != null && !aSrc.isBlank()) {
            for (final String s : aSrc.split(",")) {
                final SourceRow row = corpus.resolve(s.trim());
                if (row == null) return unknownSource(s.trim());
                if (!redistributable(row)) return notRedistributable(row);
                defaults.add(row);
            }
        }

        // Resolve everything before serving anything: a bad token means nothing served.
        final List<SourceRow> rows  = new ArrayList<>();
        final List<Span>      spans = new ArrayList<>();
        for (final String raw : aRefs.split(",")) {
            final String token = raw.trim();
            final int colon = token.indexOf(':');
            if (colon > 0) {
                final SourceRow row = corpus.resolve(token.substring(0, colon).trim());
                if (row == null) return unknownSource(token.substring(0, colon).trim());
                if (!redistributable(row)) return notRedistributable(row);
                final Span span = Span.of(token.substring(colon + 1).trim());
                if (span == null || span.crossesChapters()) return badRef(token);
                rows.add(row);
                spans.add(span);
            } else {
                if (defaults.isEmpty()) return badRequest("missing_source",
                    "Reference '" + token + "' names no edition and no src was given.", "refs");
                final Span span = Span.of(token);
                if (span == null || span.crossesChapters()) return badRef(token);
                for (final SourceRow row : defaults) {
                    rows.add(row);
                    spans.add(span);
                }
            }
        }
        if (spans.size() > MAX_REFS) {
            return badRequest("too_many_refs", "At most " + MAX_REFS + " passages per call ("
                + spans.size() + " after expanding across editions).", "refs");
        }

        final List<Map<String, Object>> groups = new ArrayList<>();
        final List<AttestationService.Passage> bundle = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            final SourceRow row  = rows.get(i);
            final Span      span = spans.get(i);
            final List<VerseRef> verses = span.select(corpus.chapter(row.id(), span.bookCode, span.chapter));
            final Map<String, Object> group = new LinkedHashMap<>();
            group.put("text", token(row));          // the token is the edition's permanent address
            group.put("direction", direction(row));
            group.putAll(passage(span, verses));
            group.put("found", !verses.isEmpty());
            groups.add(group);
            if (!verses.isEmpty()) bundle.add(AttestationService.passage(token(row), span.format(), verses));
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("passages", groups);                       // each passage names its edition; no header object
        // One tag for the combination of all the texts served (the found ones,
        // in this order, each with its edition) — the bundle verifies as one object.
        final Attestation tag = bundle.isEmpty() ? null : attest.mintBundle(bundle);
        if (tag != null) body.put("attestation", attestationJson(tag));
        final ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic());
        if (tag != null) ok.header(ATTEST_HEADER, tag.headerValue());
        return ok.body(body);
    }

    // ── Shapes ─────────────────────────────────────────────────────────────

    /**
     * An edition's public description.
     *
     * @param aRow the catalogue row
     * @return the JSON object
     */
    /**
     * An edition's token — its lowercased abbreviation, the same permanent
     * address reader links use. This, not the document id, is the identity the
     * API speaks and the attestation signs.
     *
     * @param aRow the catalogue row
     * @return the token
     */
    static String token(final SourceRow aRow) {
        return aRow.abbreviation() == null ? aRow.id() : aRow.abbreviation().toLowerCase(Locale.ROOT);
    }

    /** {@code ltr} unless the edition says otherwise. */
    static String direction(final SourceRow aRow) {
        return aRow.direction() == null || aRow.direction().isBlank() ? "ltr" : aRow.direction();
    }

    static Map<String, Object> describe(final SourceRow aRow) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", aRow.id());
        m.put("token", token(aRow));
        m.put("abbreviation", aRow.abbreviation());
        m.put("translation", aRow.name());
        m.put("type", aRow.type());
        m.put("language", aRow.language());
        m.put("direction", direction(aRow));
        m.put("year", aRow.year());
        m.put("license", aRow.license());
        m.put("downloadable", CorpusDownloadController.isPublicDomain(aRow.license()));
        return m;
    }

    private static Map<String, Object> passage(final Span aSpan, final List<VerseRef> theVerses) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", aSpan.format());
        final Map<String, Object> book = new LinkedHashMap<>();
        book.put("code", aSpan.bookCode);
        book.put("name", theVerses.isEmpty() ? null : theVerses.get(0).getBookName());
        m.put("book", book);
        m.put("chapter", aSpan.chapter);
        final List<Map<String, Object>> verses = new ArrayList<>();
        for (final VerseRef v : theVerses) {
            final Map<String, Object> vm = new LinkedHashMap<>();
            vm.put("number", v.getVerseNumber());
            vm.put("text", VerseRef.clean(v.getContent()));   // the reader's display text: no ¶/§, no stray footnotes
            if (v.getNote() != null && !v.getNote().isBlank()) vm.put("note", v.getNote());
            verses.add(vm);
        }
        m.put("verses", verses);
        return m;
    }

    private static Map<String, Object> attestationJson(final Attestation aTag) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("alg", aTag.alg());
        m.put("kid", aTag.kid());
        m.put("canon", aTag.canon());
        m.put("corpus", aTag.corpus());
        m.put("minted", aTag.minted());
        m.put("app", aTag.app());
        m.put("tag", aTag.tag());
        return m;
    }

    /**
     * Which format a request asked for: the query parameter wins, {@code Accept}
     * is the fallback, JSON the default.
     *
     * @param aFormat  the {@code format} parameter, may be {@code null}
     * @param anAccept the Accept header, may be {@code null}
     * @return {@code json}, {@code xml}, {@code text}, or {@code null} for an unknown parameter
     */
    static String negotiate(final String aFormat, final String anAccept) {
        if (aFormat != null && !aFormat.isBlank()) {
            return switch (aFormat.toLowerCase(Locale.ROOT)) {
                case "json" -> "json";
                case "xml"  -> "xml";
                case "text", "txt", "plain" -> "text";
                default -> null;
            };
        }
        if (anAccept != null) {
            final String a = anAccept.toLowerCase(Locale.ROOT);
            if (a.contains("application/xml") || a.contains("text/xml")) return "xml";
            if (a.contains("text/plain")) return "text";
        }
        return "json";
    }

    // ── Errors ─────────────────────────────────────────────────────────────

    private static ResponseEntity<Object> badRef(final String aToken) {
        return badRequest("bad_ref", "Unrecognised or cross-chapter reference '" + aToken
            + "'; nothing served.", "refs");
    }

    /**
     * Whether an edition's text may leave the platform through the API. The API
     * is not a reading surface (decision 2026-09-06): text it serves is
     * redistribution, so the gate is the download gate — the edition's own
     * {@code @license}, public domain or nothing. Licensed editions (NIV, NASB,
     * …) stay readable on the site and invisible here, whatever key is presented;
     * the About page's "not redistributed outside the platform" is made true by
     * this check. Metadata and the table of contents are not text and stay open.
     *
     * @param aRow the catalogue row
     * @return {@code true} only for public-domain editions
     */
    static boolean redistributable(final SourceRow aRow) {
        return CorpusDownloadController.isPublicDomain(aRow.license());
    }

    private static ResponseEntity<Object> notRedistributable(final SourceRow aRow) {
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(403, "not_redistributable",
                aRow.abbreviation() + " is not public domain (" + aRow.license()
                + "). Licensed editions are readable on the site but are not served "
                + "through the API, whatever key is presented. See /api/v1/texts for "
                + "downloadable editions.", "texts"));
    }

    private static ResponseEntity<Object> unknownSource(final String aSrc) {
        return notFound("unknown_source", "No edition '" + aSrc + "'. See /api/v1/texts for tokens.", "texts");
    }

    private static ResponseEntity<Object> notFound(final String aSlug, final String aMessage, final String anAnchor) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(404, aSlug, aMessage, anAnchor));
    }

    private static ResponseEntity<Object> badRequest(final String aSlug, final String aMessage, final String anAnchor) {
        return ResponseEntity.status(400).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(400, aSlug, aMessage, anAnchor));
    }

    private static ResponseEntity<Object> notYet(final String aMessage) {
        return ResponseEntity.status(501).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(501, "not_implemented", aMessage, "formats"));
    }

    // ── A reference as the corpus addresses it ─────────────────────────────

    /**
     * A parsed reference reduced to what the corpus queries need: the book code
     * (surah number for a Qur'an), the chapter (always 1 for a surah), and an
     * optional verse span. Wraps {@link ReaderLink.Ref} so the API and the reader
     * cannot drift on what {@code Q.2.255} means.
     */
    record Span(ReaderLink.Ref ref, String bookCode, int chapter, int from, int to) {

        static Span of(final String aRef) {
            final ReaderLink.Ref r = ReaderLink.Ref.parse(aRef == null ? null : aRef.trim());
            if (r == null) return null;
            final String code    = r.quran() ? String.valueOf(r.a()) : r.unit().toUpperCase(Locale.ROOT);
            final int    chapter = r.quran() ? 1 : r.a();
            final int    from    = r.b() > 0 ? r.b() : 0;
            final int    to      = r.hasEnd() ? r.endB() : from;
            return new Span(r, code, chapter, from, to);
        }

        /** A Bible range whose end names a different chapter (ISA.52.13-53.12). */
        boolean crossesChapters() {
            return !ref.quran() && ref.hasEnd() && ref.endA() != ref.a();
        }

        String format() { return ref.format(); }

        /** The verses of a chapter this span keeps: all, or the inclusive [from, to]. */
        List<VerseRef> select(final List<VerseRef> theChapter) {
            if (from <= 0) return theChapter;
            final List<VerseRef> out = new ArrayList<>();
            for (final VerseRef v : theChapter) {
                if (v.getVerseNumber() >= from && v.getVerseNumber() <= to) out.add(v);
            }
            return out;
        }
    }
}
