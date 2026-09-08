// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Serves the corpus XML for PUBLIC-DOMAIN editions: the authentic edition, with
 * this site's own bookkeeping taken back out.
 *
 * <p>Not an export format and deliberately not a conversion: it is the same
 * document the reader queries, schema and all ({@code schema/religious-text.xsd}),
 * so anyone can take a text away and do their own work with it. That is the
 * point of the project — the texts are not ours, and a reader who wants the
 * whole of the World English Bible as structured XML should not have to scrape
 * it back out of a web UI.
 *
 * <h2>What is removed on the way out, and why removing it is the honest thing</h2>
 * Three attributes are stripped from every document served here:
 * {@code @globalCanonicalSeq}, {@code @globalChronologicalSeq} and
 * {@code @globalTanakhSeq}. They are dense per-edition counters this site stamps
 * onto {@code rt:verse} after ingestion (scripts/bibles/04-06) so the reader can
 * open a verse window, keep two columns in step, and offer chronological and
 * Tanakh reading orders. No editor, translator or printer ever put them there;
 * their values encode our ordering decisions, not anything about the text. To ship
 * them would be to hand someone a Lutherbibel with our numbering baked into it and
 * let them mistake it for the edition's own. Whoever downloads a text should get
 * the text; if they want a reading order they can decide on their own.
 *
 * <p>Everything else stays, including {@code @basedOn} lineage and the source
 * metadata, because those are claims about the edition rather than about this
 * site's plumbing, and a copy that cannot say where it came from is worth less.
 *
 * <p>The strip is an XQuery {@code delete node} against the attribute nodes
 * themselves, not a search-and-replace over serialised markup, so it cannot
 * disturb anything inside verse text that merely looks like an attribute.
 *
 * <h2>The gate is default-deny, and lives here rather than in a list</h2>
 * A hand-maintained list of "downloadable" ids is a licence breach waiting for
 * someone to add an edition and forget. Instead every request re-reads the
 * edition's own {@code @license} attribute out of the corpus and serves the
 * document ONLY if that attribute says public domain. An edition with a missing,
 * empty, unexpected or merely unrecognised licence is refused. Adding a new
 * text can therefore never accidentally publish it; getting it wrong fails
 * closed, with a 403 rather than a leak.
 *
 * <p>This deliberately withholds some texts that are in fact redistributable —
 * the Hindi IRV is CC BY-SA and the Turkish YTC is CC BY-ND, and both permit
 * verbatim copying with attribution. Serving them needs an attribution notice
 * travelling with the file and, for YTC, the unresolved no-derivatives question
 * in the deploy checklist settled first. Those are decisions, not defaults, so
 * they are not silently folded into "public domain".
 *
 * <p>Licensed texts (NIV, NASB, NBLA, AEUUT, NAV) can never be served here: the
 * About page's own attribution note states that redistribution outside the
 * platform is not permitted, and this endpoint has to be the thing that makes
 * that true rather than a sentence that hopes it is.
 */
@RestController
@RequestMapping("/download")
public class CorpusDownloadController {

    private static final Logger log = LoggerFactory.getLogger(CorpusDownloadController.class);

    /** Document ids are corpus-controlled slugs; anything else is refused before
     *  it can reach an XQuery string or a document path. */
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private static final String NS_DECL =
        "declare namespace rt='http://religioustext.org/schema/1.0'; ";

    /** This site's reading-order bookkeeping, stamped after ingestion and removed
     *  again here. See the class comment; adding one to the corpus means adding it
     *  to this list, or the next download quietly ships it. */
    private static final List<String> SEQUENCE_ATTRIBUTES = List.of(
        "globalCanonicalSeq", "globalChronologicalSeq", "globalTanakhSeq");

    private final RestTemplate restTemplate;
    private final BaseXProperties baseX;

    public CorpusDownloadController(final RestTemplate aRestTemplate,
                                    final BaseXProperties aBaseXProperties) {
        this.restTemplate = aRestTemplate;
        this.baseX = aBaseXProperties;
    }

    /**
     * Plain-text index of what may be downloaded: {@code id  abbreviation  translation}.
     * Generated from the corpus, so it cannot list something the download route
     * would then refuse.
     *
     * @return one line per downloadable edition
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> index() {
        final String xquery = NS_DECL
            + "for $t in db:open('" + baseX.database() + "')//rt:text "
            + "where starts-with(lower-case(normalize-space(string($t/@license))), 'public domain') "
            + "order by string($t/@id) "
            + "return string-join((string($t/@id), string($t/@abbreviation), "
            + "string($t/@translation), string($t/@license)), '\t')";

        final List<String> rows = query(xquery);
        final StringBuilder out = new StringBuilder();
        out.append("# Public-domain texts, as the XML the reader itself queries.\n")
           .append("# GET /download/{id} returns the document; see schema/religious-text.xsd.\n")
           .append("# This site's reading-order attributes (global*Seq) are stripped on the way\n")
           .append("# out: you get the edition, not our numbering.\n")
           .append("# Licensed and CC-licensed editions are not served here — see /about.\n#\n")
           .append("# id\tabbr\ttranslation\tlicence\n");
        rows.forEach(r -> out.append(r).append('\n'));
        return ResponseEntity.ok(out.toString());
    }

    /**
     * The corpus document for one edition, if and only if it is public domain.
     *
     * @param aKey the edition id ({@code bible-web}) or its abbreviation ({@code WEB});
     *             the About page's cards know only the latter
     * @return the corpus XML as an attachment, minus this site's {@code global*Seq}
     *         attributes; 404 if unknown, 403 if not public domain
     */
    @GetMapping("/{key}")
    public ResponseEntity<String> download(@PathVariable("key") final String aKey) {
        if (aKey == null || !SAFE_KEY.matcher(aKey).matches()) {
            return ResponseEntity.notFound().build();
        }

        // Accepts either the document id (bible-asv-1901) or the abbreviation the
        // About page shows on the card (ASV, Q-AR). The cards only know the
        // abbreviation, and threading ids through thirty call sites to avoid one
        // lookup would be the worse trade.
        final List<String> row = query(NS_DECL
            + "let $k := '" + aKey.replace("'", "''") + "' "
            + "for $t in db:open('" + baseX.database() + "')//rt:text "
            + "where string($t/@id) = $k "
            + "   or upper-case(string($t/@abbreviation)) = upper-case($k) "
            + "return string-join((string($t/@id), string($t/@license)), '|')");

        if (row.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_PLAIN)
                .body("No such text: " + aKey + "\nSee /download for the list.\n");
        }

        final String[] parts = row.get(0).split("\\|", -1);
        final String resolvedId = parts[0];
        final String licence = parts.length > 1 ? parts[1] : "";

        if (!isPublicDomain(licence)) {
            log.info("Refused download of non-public-domain text {} (licence: {})",
                     resolvedId, licence);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.TEXT_PLAIN)
                .body("\"" + resolvedId + "\" is not public domain (licence: " + licence
                    + ").\nOnly public-domain texts may be redistributed from here."
                    + "\nSee /download for what is available.\n");
        }

        final String document = fetchDocument(resolvedId);
        if (document == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Could not read " + resolvedId + " from the corpus.\n");
        }

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + resolvedId + ".xml\"");
        // Corpus documents change only on re-ingestion; a day is a fair compromise
        // between a 10 MB file and a text that has not moved in months.
        headers.setCacheControl("public, max-age=86400");
        return new ResponseEntity<>(document, headers, HttpStatus.OK);
    }

    /**
     * Whether a corpus {@code @license} value permits redistribution from here.
     *
     * <p>Prefix match on "public domain" so the LDS wording
     * ("Public domain (LDS standard works; copyrighted apparatus excluded)")
     * is covered without enumerating variants. Everything else — including
     * absent, blank and CC licences — is refused. Widening this is a licence
     * decision; do not widen it to make a download work.
     *
     * @param aLicense the raw attribute value, may be {@code null}
     * @return {@code true} only for public-domain texts
     */
    public static boolean isPublicDomain(final String aLicense) {
        if (aLicense == null || aLicense.isBlank()) return false;
        final String v = aLicense.trim().toLowerCase(Locale.ROOT);
        if (!v.startsWith("public domain")) return false;

        // "Public domain" on its own, or qualified only by a parenthetical
        // attribution — "Public domain (M. Pickthall, 1930)", "Public domain (LDS
        // standard works; copyrighted apparatus excluded)".
        final String rest = v.substring("public domain".length()).trim();
        if (rest.isEmpty() || rest.startsWith("(")) return true;

        // Anything else qualifies the claim, and the qualification is the point.
        // Yusuf Ali is recorded as "Public domain in EU/life+70 & Pakistan …; US
        // URAA copyright to 2033" — a plain prefix match would have served a text
        // still in copyright in the United States to anyone who asked. A licence
        // that needs a sentence to explain where it applies is not one this
        // endpoint can honour, because it cannot know where the requester is.
        return false;
    }

    /**
     * The XQuery that returns one corpus document with the sequence attributes gone.
     *
     * <p>A transform expression rather than a string edit: {@code delete node} takes
     * attribute nodes, so a verse whose text happened to contain the characters
     * {@code globalCanonicalSeq="1"} is untouched, and a serialisation change in
     * BaseX cannot silently defeat it. The copy is made and discarded per request;
     * the stored document is never modified.
     *
     * @param aDatabase the BaseX database name
     * @param anId      the resolved document id, already known to the corpus
     * @return an XQuery returning the document node
     */
    static String authenticCopyQuery(final String aDatabase, final String anId) {
        final String targets = SEQUENCE_ATTRIBUTES.stream()
            .map(a -> "$doc//@" + a)
            .collect(Collectors.joining(", "));
        return "declare option output:omit-xml-declaration 'no'; "
             + "declare option output:indent 'no'; "
             + "copy $doc := db:open('" + aDatabase.replace("'", "''") + "', '"
             + anId.replace("'", "''") + ".xml') "
             + "modify delete node (" + targets + ") "
             + "return $doc";
    }

    private String fetchDocument(final String anId) {
        try {
            final URI uri = UriComponentsBuilder
                .fromHttpUrl(baseX.uri() + "/" + baseX.database())
                .queryParam("query", authenticCopyQuery(baseX.database(), anId))
                .build(false)
                .encode(StandardCharsets.UTF_8)
                .toUri();
            final ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(authHeaders("application/xml")), String.class);
            return response.getBody();
        } catch (final RuntimeException e) {
            log.error("Failed reading corpus document {}", anId, e);
            return null;
        }
    }

    private List<String> query(final String anXquery) {
        final List<String> results = new ArrayList<>();
        try {
            final URI uri = UriComponentsBuilder
                .fromHttpUrl(baseX.uri() + "/" + baseX.database())
                .queryParam("query", anXquery)
                .build(false)
                .encode(StandardCharsets.UTF_8)
                .toUri();
            final ResponseEntity<String> response = restTemplate.exchange(
                uri, HttpMethod.GET, new HttpEntity<>(authHeaders("text/plain")), String.class);
            final String body = response.getBody();
            if (body != null && !body.isBlank()) {
                for (final String line : body.split("\n")) {
                    if (!line.isBlank()) results.add(line.trim());
                }
            }
        } catch (final RuntimeException e) {
            log.error("Corpus query failed", e);
        }
        return results;
    }

    private HttpHeaders authHeaders(final String anAccept) {
        final HttpHeaders headers = new HttpHeaders();
        final String encoded = Base64.getEncoder().encodeToString(
            (baseX.username() + ":" + baseX.password()).getBytes(StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encoded);
        headers.set("Accept", anAccept);
        return headers;
    }
}
