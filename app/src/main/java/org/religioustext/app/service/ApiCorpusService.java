package org.religioustext.app.service;

import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.religioustext.app.model.DisplayOptions;
import org.religioustext.app.model.VerseRef;
import org.religioustext.app.ui.views.reader.SourceCatalog;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The corpus reads the web API needs (docs/api-spec.md §3.2–§3.5): the
 * source catalogue, a chapter's verses, and a chapter as a schema-pure XML
 * subtree. Everything scripture-shaped goes through {@link TextQueryService}
 * — the API reads the reader's queries, it does not invent its own — except
 * the XML cut, which is a new query for a new need.
 *
 * <p>The catalogue is cached for {@value #CATALOG_SECONDS} seconds: it changes
 * only on a corpus push, and every API call needs it to resolve a token.
 *
 * <p>Read-only by construction: nothing here issues a BaseX write, so the
 * "writes must use curl, never RestTemplate" project rule never applies.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Service
public class ApiCorpusService {

    private static final Logger log = LoggerFactory.getLogger(ApiCorpusService.class);

    static final int CATALOG_SECONDS = 60;

    private static final String NS_DECL =
        "declare namespace rt='http://religioustext.org/schema/1.0'; ";

    /** Corpus-controlled slugs only; anything else never reaches an XQuery string. */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final TextQueryService texts;
    private final BaseXProperties  baseX;
    private final RestTemplate     restTemplate;
    private final Clock            clock;

    private volatile CachedCatalog cached;

    public ApiCorpusService(
             final TextQueryService aTextQueryService
            , final BaseXProperties aBaseXProperties
            , final RestTemplate aRestTemplate
            , final Clock aClock) {
        this.texts        = aTextQueryService;
        this.baseX        = aBaseXProperties;
        this.restTemplate = aRestTemplate;
        this.clock        = aClock;
    }

    /**
     * The source catalogue, cached briefly.
     *
     * @return the catalogue over the reader's own {@code listSources()} rows
     */
    public SourceCatalog catalog() {
        final Instant now = clock.instant();
        CachedCatalog c = cached;
        if (c == null || now.isAfter(c.takenAt.plusSeconds(CATALOG_SECONDS))) {
            c = new CachedCatalog(now, new SourceCatalog(texts.listSources()));
            cached = c;
        }
        return c.catalog;
    }

    /**
     * Resolve a source token the way the reader does — abbreviation,
     * case-insensitive — or, unlike the reader, the document id; the About
     * page's cards know the abbreviation, scripts often know the id, and
     * {@code /download} already accepts both. Never falls back to a default
     * Bible: an API must not guess (docs/api-test-cases.md P11).
     *
     * @param aToken the {@code {src}} path segment
     * @return the source row, or {@code null} when unknown or unsafe
     */
    public SourceRow resolve(final String aToken) {
        if (aToken == null || !SAFE_KEY.matcher(aToken).matches()) return null;
        final SourceCatalog catalog = catalog();
        String[] row = catalog.byToken(aToken);
        if (row == null) row = catalog.byId(aToken);
        return row == null ? null : SourceRow.of(row);
    }

    /**
     * One book of an edition's table of contents: code, names, and the chapter
     * numbers actually present (a selective edition — Agricola's Exodus starts
     * at 15 — lists only what exists).
     *
     * @param code       the USFM book code, or the surah number for a Qur'an
     * @param name       the book's name in the edition's language
     * @param nativeName the Arabic name for a surah, else {@code null}
     * @param chapters   the chapter numbers present, in order
     */
    public record Book(String code, String name, String nativeName, List<Integer> chapters) { }

    /**
     * An edition's table of contents, via the reader's own sidebar query.
     *
     * @param aSourceId the edition document id
     * @return the books in canonical order, empty when the edition has none
     */
    public List<Book> books(final String aSourceId) {
        final List<Book> out = new ArrayList<>();
        for (final String[] row : texts.listBooksWithChapterCounts(
                aSourceId, DisplayOptions.OrderMode.CANONICAL)) {
            // row: name | chapterCount | arabicName | code | "1,2,3,…"
            if (row.length < 5) continue;
            final List<Integer> chapters = new ArrayList<>();
            for (final String n : row[4].split(",")) {
                try { if (!n.isBlank()) chapters.add(Integer.parseInt(n.trim())); }
                catch (final NumberFormatException ignored) { /* a malformed number is not a chapter */ }
            }
            out.add(new Book(row[3], row[0], row[2] == null || row[2].isBlank() ? null : row[2], chapters));
        }
        return out;
    }

    /**
     * A chapter's verses, via the reader's own query.
     *
     * @param aSourceId the edition document id
     * @param aBookCode the USFM book code, or the surah number for a Qur'an
     * @param aChapter  the chapter (1 for a surah — Qur'an addressing has no middle level)
     * @return the verses in document order, empty when the chapter does not exist
     */
    public List<VerseRef> chapter(final String aSourceId, final String aBookCode, final int aChapter) {
        return texts.versesByCodeChapter(aSourceId, aBookCode, aChapter);
    }

    /**
     * A chapter as the {@code rt:chapter} element it is in the corpus — a valid
     * subtree of {@code religious-text.xsd}, never an API dialect — with this
     * site's reading-order attributes ({@code global*Seq}) removed exactly as the
     * bulk download removes them, so the same chapter cut from the download and
     * fetched here are the same bytes. Optionally narrowed to a verse span, in
     * which case the other verses are deleted from the copy (still a valid
     * {@code rt:chapter}).
     *
     * @param aSourceId  the edition document id
     * @param aBookCode  the book code
     * @param aChapter   the chapter number
     * @param aFromVerse first verse to keep, or 0 for the whole chapter
     * @param aToVerse   last verse to keep (inclusive), or 0 for "through the end"
     * @return the serialised element, or {@code null} when the chapter does not exist
     */
    public String chapterXml(final String aSourceId, final String aBookCode, final int aChapter,
                             final int aFromVerse, final int aToVerse) {
        if (!SAFE_KEY.matcher(aSourceId).matches() || !SAFE_KEY.matcher(aBookCode).matches()) return null;
        final StringBuilder deletes = new StringBuilder(
            "delete node $c//@globalCanonicalSeq, delete node $c//@globalChronologicalSeq, "
          + "delete node $c//@globalTanakhSeq");
        if (aFromVerse > 0) {
            deletes.append(", delete node $c/rt:verse[xs:integer(@number) < ").append(aFromVerse).append(']');
            if (aToVerse >= aFromVerse) {
                deletes.append(", delete node $c/rt:verse[xs:integer(@number) > ").append(aToVerse).append(']');
            } else {
                deletes.append(", delete node $c/rt:verse[xs:integer(@number) > ").append(aFromVerse).append(']');
            }
        }
        final String xquery = NS_DECL
            + "declare option output:omit-xml-declaration 'yes'; "
            + "declare option output:indent 'no'; "
            + "let $src := db:open('" + baseX.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book[@code='" + aBookCode.replace("'", "''") + "']"
            + "/rt:chapter[@number='" + aChapter + "'] "
            + "return if (empty($src)) then () else "
            + "copy $c := $src[1] modify (" + deletes + ") return $c";
        final String xml = fetch(xquery);
        return xml == null || xml.isBlank() ? null : xml;
    }

    private String fetch(final String anXquery) {
        try {
            final URI uri = UriComponentsBuilder
                .fromHttpUrl(baseX.uri() + "/" + baseX.database())
                .queryParam("query", anXquery)
                .build(false)
                .encode(StandardCharsets.UTF_8)
                .toUri();
            final HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (baseX.username() + ":" + baseX.password()).getBytes(StandardCharsets.UTF_8)));
            headers.set("Accept", "application/xml");
            final ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getBody();
        } catch (final RuntimeException e) {
            log.error("Corpus XML query failed", e);
            return null;
        }
    }

    private record CachedCatalog(Instant takenAt, SourceCatalog catalog) { }
}
