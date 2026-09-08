package org.religioustext.app.service;

import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.religioustext.app.model.DisplayOptions;
import org.religioustext.app.model.VerseRef;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Queries religious texts from BaseX via its REST API using XQuery.
 * Each translation is stored as one document ({id}.xml) in canonical order,
 * so all queries are simple single-document traversals — no deduplication needed.
 */
@Service
public class TextQueryService {

    private static final Logger log = LoggerFactory.getLogger(TextQueryService.class);

    private static final String NS_DECL =
        "declare namespace rt='http://religioustext.org/schema/1.0'; ";

    private final BaseXProperties baseXProperties;
    private final RestTemplate    restTemplate;

    public TextQueryService(
             final BaseXProperties aBaseXProperties
            , final RestTemplate    aRestTemplate) {
        this.baseXProperties = aBaseXProperties;
        this.restTemplate    = aRestTemplate;
    }

    // ── Source listing ────────────────────────────────────────────────

    public List<String[]> listSources() {
        final String xquery = NS_DECL
            + "for $t in db:open('" + baseXProperties.database() + "')//rt:text "
            + "order by string($t/@abbreviation) "
            + "return string-join((string($t/@id),string($t/@translation),string($t/@abbreviation),string($t/@direction),string($t/@license),string($t/@source),string($t/@type),string(($t/@lang, $t/@bcp47Language)[1]),string($t/@baseText),string($t/@basedOn),string($t/@original),string($t/@year)),'|')";

        return executeQuery(xquery).stream()
            .map(row -> row.split("\\|", -1))
            .toList();
    }

    // ── Book listing ──────────────────────────────────────────────────

    public List<String[]> listBooksWithChapterCounts(final String aSourceId, final DisplayOptions.OrderMode anOrderMode) {
        // Both alternate orders fall back to @globalCanonicalSeq when the book is
        // unstamped, as listChronologicalChunks and streamVerses already do. Without
        // it, xs:integer(()) is the empty sequence, every unstamped book ties, and
        // the sidebar lists them in whatever order the tie-break happens to surface.
        // The stampers resolve book names through an English + Spanish map only
        // (scripts/bibles/05_stamp_chronological.py), so an edition whose @name is
        // in another language — the Finnish ones, Agricola first among them — is
        // exactly the case that hits this branch.
        final String orderAttr = switch (anOrderMode) {
            case CHRONOLOGICAL -> "xs:integer(($b/rt:chapter[1]/rt:verse[1]/@globalChronologicalSeq, $b/rt:chapter[1]/rt:verse[1]/@globalCanonicalSeq)[1])";
            case TANAKH        -> "xs:integer(($b/rt:chapter[1]/rt:verse[1]/@globalTanakhSeq, $b/rt:chapter[1]/rt:verse[1]/@globalCanonicalSeq)[1])";
            default            -> "xs:integer($b/@canonicalOrder)";
        };
        final String xquery = NS_DECL
            + "for $b in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book "
            + "order by " + orderAttr + " "
            + "return string-join((string($b/@name), string(count($b/rt:chapter)), string($b/@arabicName), string($b/@code), "
            + "string-join(for $c in $b/rt:chapter order by xs:integer($c/@number) return string($c/@number), ',')), '|')";

        return executeQuery(xquery).stream()
            .map(row -> row.split("\\|", -1))
            .toList();
    }

    // ── Chronological chunks ──────────────────────────────────────────

    /**
     * Returns every (book, chapter) pair in this source ordered by the chapter's
     * minimum chronological sequence — i.e. when chronological reading reaches
     * each chapter for the first time. Verses without @globalChronologicalSeq
     * fall back to @globalCanonicalSeq (apocrypha / unstamped books sort to the
     * end). Each row is {bookName, chapterNumber} as strings; ~1,200 rows for a
     * full Bible. The order may revisit a book non-contiguously (Genesis 1-11,
     * then Job, then Genesis 12-50) — that's the whole point. Callers receive
     * the chronological skeleton and lazy-load one chunk at a time via the same
     * appendChapter() machinery the canonical reader already uses.
     */
    public List<String[]> listChronologicalChunks(final String aSourceId) {
        final String xquery = NS_DECL
            + "for $c in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book/rt:chapter "
            + "let $minSeq := min(for $v in $c/rt:verse "
            + "                   return (if (exists($v/@globalChronologicalSeq)) "
            + "                          then xs:integer($v/@globalChronologicalSeq) "
            + "                          else xs:integer($v/@globalCanonicalSeq))) "
            + "order by $minSeq "
            + "return string-join((string($c/parent::rt:book/@name), string($c/@number)), '|')";

        return executeQuery(xquery).stream()
            .map(row -> row.split("\\|", -1))
            .toList();
    }

    // ── Verse fetching ────────────────────────────────────────────────

    public List<VerseRef> getVerses(
             final String         aSourceId
            , final String         aBookName
            , final int            aChapterNumber
            , final DisplayOptions anOptions) {

        final String xquery = NS_DECL
            + "let $book := db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book[@name='" + aBookName + "'] "
            + "let $code := string($book/@code) "
            + "for $v in $book/rt:chapter[@number='" + aChapterNumber + "']/rt:verse "
            + "order by xs:integer($v/@number) "
            + "return string-join(("
            + "string($v/@number),string($v/@bookName),string($v/@bookAltName),"
            + "string($v/@chapterNumber),string($v/@chapterTitle),"
            + "string($v/@globalCanonicalSeq),string($v/@globalChronologicalSeq),"
            + "string($v/@globalNarrativeSeq),string($v/@note),string($v),"
            + "$code"
            + "),'|||')";

        return executeQuery(xquery).stream()
            .map(row -> parseVerseRow(row, aSourceId))
            .toList();
    }

    // ── Streaming mode ────────────────────────────────────────────────

    public List<VerseRef> streamVerses(
             final String         aSourceId
            , final DisplayOptions anOptions) {

        final String orderAttr = switch (anOptions.getOrderMode()) {
            case CHRONOLOGICAL -> "@globalChronologicalSeq";
            case TANAKH        -> "@globalTanakhSeq";
            default            -> "@globalCanonicalSeq";
        };

        final String xquery = NS_DECL
            + "for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text//rt:verse "
            + "let $seq := if (exists($v/" + orderAttr + ")) "
            + "            then xs:integer($v/" + orderAttr + ") "
            + "            else xs:integer($v/@globalCanonicalSeq) "
            + "order by $seq "
            + "return string-join(("
            + "string($v/@number),string($v/@bookName),string($v/@bookAltName),"
            + "string($v/@chapterNumber),string($v/@chapterTitle),"
            + "string($v/@globalCanonicalSeq),string($v/@globalChronologicalSeq),"
            + "string($v/@globalNarrativeSeq),string($v/@note),string($v)"
            + "),'|||')";

        return executeQuery(xquery).stream()
            .map(row -> parseVerseRow(row, aSourceId))
            .toList();
    }

    // ── Windowed verse fetching (unified reader loader) ────────────────
    //
    // The reader's single loading primitive. Instead of loading a whole book
    // (canonical) or a (book, chapter) chunk (chronological), it loads a fixed
    // COUNT of verses ordered by whichever sequence the active OrderMode names.
    // Every load adds a near-constant amount of content regardless of book or
    // mode, which keeps scroll speed uniform (a screen of short Psalms verses
    // and a screen of long Genesis verses load the same NUMBER of verses; the
    // pixel-based scroll trigger adapts to the rendered height either way).
    //
    // The seq space may be sparse (NIV drops verse-numbered heading nodes, so
    // there are gaps), so windows are expressed as "the N verses at or after
    // fromSeq" rather than "seq in [fromSeq, fromSeq+N]" — a count, not a range,
    // so a sparse region still yields a full batch.

    /** The seq attribute name for an order mode. */
    private static String seqAttr(final DisplayOptions.OrderMode anOrderMode) {
        return switch (anOrderMode) {
            case CHRONOLOGICAL -> "@globalChronologicalSeq";
            case TANAKH        -> "@globalTanakhSeq";
            default            -> "@globalCanonicalSeq";
        };
    }

    /** Shared XQuery let-binding that computes the active seq for a verse $v.
     *  When the active attribute is absent (apocrypha / deuterocanon verses that
     *  no chronological plan covers), the verse is pushed to the END of the
     *  ordering via a large offset on its canonical seq — NOT folded into the
     *  canonical-seq number space, which would collide with legitimately-stamped
     *  chronological values and interleave apocrypha into the canonical text
     *  (the 1 Chronicles 7/13 artifact). Unstamped verses thus sort after all
     *  stamped verses, in canonical order among themselves, as intended. For
     *  CANONICAL mode the attribute is always present (every verse is canonically
     *  stamped), so the else branch never fires there. */
    private static String seqLet(final DisplayOptions.OrderMode anOrderMode) {
        final String attr = seqAttr(anOrderMode);
        return "let $seq := if (exists($v/" + attr + ")) "
             + "           then xs:integer($v/" + attr + ") "
             + "           else sum((10000000, xs:integer($v/@globalCanonicalSeq))) ";
    }

    private static final String VERSE_RETURN =
          "return string-join(("
        + "string($v/@number),string($v/@bookName),string($v/@bookAltName),"
        + "string($v/@chapterNumber),string($v/@chapterTitle),"
        + "string($v/@globalCanonicalSeq),string($v/@globalChronologicalSeq),"
        + "string($v/@globalNarrativeSeq),string($v/@note),string($v),"
        + "string($v/ancestor::rt:book/@code)"
        + "),'|||')";

    /**
     * The lowest active-seq value in this source — the very start of the text
     * in the active order. Used as the default anchor when opening a source
     * with no specific target. Returns 0 if the source is empty.
     */
    public int minSeq(final String aSourceId, final DisplayOptions.OrderMode anOrderMode) {
        final String xquery = NS_DECL
            + "min(for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text//rt:verse "
            + seqLet(anOrderMode)
            + "return $seq)";
        final List<String> r = executeQuery(xquery);
        return r.isEmpty() ? 0 : parseFirstInt(r.get(0));
    }

    /**
     * The active-seq value for a specific (book, chapter) — specifically the
     * MINIMUM seq among that chapter's verses, i.e. the seq at which reading in
     * the active order first reaches that chapter. This is how a book-dropdown
     * pick or a deep link resolves to a window anchor. Returns -1 if not found.
     */
    public int seqForBookChapter(final String aSourceId,
                                 final DisplayOptions.OrderMode anOrderMode,
                                 final String aBookName,
                                 final int aChapterNumber) {
        final String xquery = NS_DECL
            + "min(for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book[@name='" + xqEscape(aBookName) + "']"
            + "/rt:chapter[@number='" + aChapterNumber + "']/rt:verse "
            + seqLet(anOrderMode)
            + "return $seq)";
        final List<String> r = executeQuery(xquery);
        if (r.isEmpty() || r.get(0).isBlank()) return -1;
        return parseFirstInt(r.get(0));
    }

    /**
     * The active-seq value for a specific (book, chapter, verse) — verse-level
     * resolution for a shareable reference link (e.g. SNG.2.16). When {@code verse <= 0}
     * or the exact verse isn't found, falls back to the chapter anchor
     * (seqForBookChapter). Returns -1 if the (book, chapter) has no verses.
     */
    public int seqForRef(final String aSourceId,
                         final DisplayOptions.OrderMode anOrderMode,
                         final String aBookName,
                         final int aChapterNumber,
                         final int aVerseNumber) {
        if (aVerseNumber > 0) {
            final String xquery = NS_DECL
                + "for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
                + "/rt:text/rt:book[@name='" + xqEscape(aBookName) + "']"
                + "/rt:chapter[@number='" + aChapterNumber + "']"
                + "/rt:verse[@number='" + aVerseNumber + "'] "
                + seqLet(anOrderMode)
                + "return $seq";
            final List<String> r = executeQuery(xquery);
            if (!r.isEmpty() && !r.get(0).isBlank()) return parseFirstInt(r.get(0));
        }
        return seqForBookChapter(aSourceId, anOrderMode, aBookName, aChapterNumber);
    }

    /**
     * The COUNT verses at or after fromSeq, in active-seq order — the forward
     * (downward) load batch. Fewer than count are returned near the end of the
     * text.
     */
    public List<VerseRef> verseWindowFrom(final String aSourceId,
                                          final DisplayOptions anOptions,
                                          final int aFromSeq,
                                          final int aCount) {
        final String xquery = NS_DECL
            + "(for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text//rt:verse "
            + seqLet(anOptions.getOrderMode())
            + "where $seq >= " + aFromSeq + " "
            + "order by $seq "
            + VERSE_RETURN + ")[position() <= " + aCount + "]";
        return executeQuery(xquery).stream()
            .map(row -> parseVerseRow(row, aSourceId))
            .toList();
    }

    /**
     * The COUNT verses strictly before beforeSeq, in active-seq order — the
     * backward (upward) load batch. Returned in ascending seq order (ready to
     * prepend in reading order). Fewer than count near the start of the text.
     */
    public List<VerseRef> verseWindowBefore(final String aSourceId,
                                            final DisplayOptions anOptions,
                                            final int aBeforeSeq,
                                            final int aCount) {
        // Take the LAST `count` verses below beforeSeq: order descending, slice,
        // then the caller/Java re-sorts ascending. Doing the slice in XQuery
        // keeps the payload to `count` rows rather than everything-before.
        final String xquery = NS_DECL
            + "(for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text//rt:verse "
            + seqLet(anOptions.getOrderMode())
            + "where $seq < " + aBeforeSeq + " "
            + "order by $seq descending "
            + VERSE_RETURN + ")[position() <= " + aCount + "]";
        // Rows come back descending; reverse to ascending reading order.
        final List<VerseRef> desc = executeQuery(xquery).stream()
            .map(row -> parseVerseRow(row, aSourceId))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        java.util.Collections.reverse(desc);
        return desc;
    }

    /**
     * Companion fetch for in-column pairing (e.g. a Qur'an translation shown
     * beneath the Arabic). Returns every verse of sourceId whose
     * {@code @globalCanonicalSeq} lies in [fromCanon, toCanon], ordered by that seq.
     * Pairing is by canonical seq, which is identical across coupled editions
     * (the Arabic base and each translation share it per ayah), so the caller
     * maps the result by getGlobalCanonicalSeq() and looks up each rendered
     * verse's companion text by its own canonical seq.
     */
    public List<VerseRef> versesByCanonicalRange(final String aSourceId,
                                                 final int aFromCanon,
                                                 final int aToCanon) {
        final String xquery = NS_DECL
            + "for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text//rt:verse "
            + "where xs:integer($v/@globalCanonicalSeq) >= " + aFromCanon
            + " and xs:integer($v/@globalCanonicalSeq) <= " + aToCanon + " "
            + "order by xs:integer($v/@globalCanonicalSeq) "
            + VERSE_RETURN;
        return executeQuery(xquery).stream()
            .map(row -> parseVerseRow(row, aSourceId))
            .toList();
    }

    /**
     * All verses of one chapter addressed by BOOK CODE — the edition- and
     * language-independent axis (USFM code / surah number / LDS code). This is
     * how the lineage rungs align across editions: {@code @globalCanonicalSeq}
     * is stamped per-edition and does NOT agree between two Bibles (the
     * seq-aligned Qur'an companions share one base edition, Bibles don't), but
     * GEN.1.1 names the same verse in every edition that has it. Returns the
     * verses in document order; empty when the edition lacks the book/chapter
     * (which is exactly the rung-pruning signal).
     *
     * @param aSourceId      the edition to read
     * @param aBookCode      the book's stable code (e.g. "GEN")
     * @param aChapterNumber the chapter within that book
     * @return the chapter's verses, or empty
     */
    public List<VerseRef> versesByCodeChapter(final String aSourceId,
                                              final String aBookCode,
                                              final int aChapterNumber) {
        final String xquery = NS_DECL
            + "for $v in db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book[@code='" + xqEscape(aBookCode) + "']"
            + "/rt:chapter[@number='" + aChapterNumber + "']/rt:verse "
            + VERSE_RETURN;
        return executeQuery(xquery).stream()
            .map(row -> parseVerseRow(row, aSourceId))
            .toList();
    }

    /**
     * Whether an edition has any verse at all in a book, by standard code.
     *
     * <p>Asked without naming a chapter on purpose. The obvious version of this
     * question — "does chapter 1 have verses?" — is wrong for any edition that
     * prints selections: Agricola's Exodus begins at chapter 15, so chapter 1 is
     * empty and the book is not.
     *
     * @param aSourceId the edition document id
     * @param aBookCode the standard book code (USFM)
     * @return {@code true} if the book is present with at least one verse
     */
    public boolean hasBookCode(final String aSourceId, final String aBookCode) {
        final String xquery = NS_DECL
            + "boolean(db:open('" + baseXProperties.database() + "', '" + aSourceId + ".xml')"
            + "/rt:text/rt:book[@code='" + xqEscape(aBookCode) + "']//rt:verse[1])";
        final List<String> rows = executeQuery(xquery);
        return !rows.isEmpty() && "true".equalsIgnoreCase(rows.get(0).trim());
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** Escape single quotes in a book name for safe embedding in an XQuery
     *  string literal (e.g. a book named with an apostrophe). */
    private static String xqEscape(final String aString) {
        return aString == null ? "" : aString.replace("'", "''");
    }

    private int parseFirstInt(final String aString) {
        try { return Integer.parseInt(aString.trim()); }
        catch (final Exception e) { return 0; }
    }

    private List<String> executeQuery(final String anXquery) {
        final List<String> results = new ArrayList<>();
        try {
            final URI uri = UriComponentsBuilder
                .fromHttpUrl(baseXProperties.uri() + "/" + baseXProperties.database())
                .queryParam("query", anXquery)
                .build(false)
                .encode(StandardCharsets.UTF_8)
                .toUri();

            final HttpHeaders headers = new HttpHeaders();
            final String credentials = baseXProperties.username()
                + ":" + baseXProperties.password();
            final String encoded = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + encoded);
            headers.set("Accept", "text/plain");

            final ResponseEntity<String> response = restTemplate.exchange(
                 uri
                , HttpMethod.GET
                , new HttpEntity<>(headers)
                , String.class);

            if (response.getBody() != null && !response.getBody().isBlank()) {
                for (final String line : response.getBody().split("\n")) {
                    if (!line.isBlank()) results.add(line.trim());
                }
            }
        } catch (final Exception e) {
            log.error("XQuery execution failed: {}", e.getMessage(), e);
        }
        return results;
    }

    private VerseRef parseVerseRow(final String aRow, final String aSourceId) {
        final String[] parts = aRow.split("\\|\\|\\|", -1);
        return VerseRef.builder()
            .sourceId(aSourceId)
            .verseNumber(parseIntSafe(parts, 0))
            .bookName(partAt(parts, 1))
            .bookAltName(partAt(parts, 2))
            .chapterNumber(parseIntSafe(parts, 3))
            .chapterTitle(partAt(parts, 4))
            .globalCanonicalSeq(parseIntegerSafe(parts, 5))
            .globalChronologicalSeq(parseIntegerSafe(parts, 6))
            .globalNarrativeSeq(parseIntegerSafe(parts, 7))
            .note(partAt(parts, 8))
            .content(partAt(parts, 9))
            .bookCode(partAt(parts, 10))
            .build();
    }

    private String partAt(final String[] theParts, final int anIndex) {
        return (anIndex < theParts.length && !theParts[anIndex].isBlank()) ? theParts[anIndex] : null;
    }

    private int parseIntSafe(final String[] theParts, final int anIndex) {
        try { return Integer.parseInt(theParts[anIndex].trim()); }
        catch (final Exception e) { return 0; }
    }

    private Integer parseIntegerSafe(final String[] theParts, final int anIndex) {
        try {
            final String val = theParts[anIndex].trim();
            return val.isEmpty() ? null : Integer.parseInt(val);
        } catch (final Exception e) { return null; }
    }
}
