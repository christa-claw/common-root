// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Read side of the word layer (docs/strongs-design.md): the original-language words of an
 * edition with their Strong's numbers, from the BaseX {@code words} database that
 * {@code scripts/strongs/import_words.py} fills. The corpus's verse text is never read or
 * changed here.
 *
 * <p>Only verses whose source text matched the edition's own text exactly ({@code align="exact"})
 * are served, so a number never lands on text that is not on the page. The caller should still
 * compare {@link #reconstruct(List)} with the line it is about to replace, which makes that
 * guarantee independent of the data.
 *
 * <p>When the database is absent (an environment that has not imported it) every method answers
 * "no layer" and the reader renders exactly as before.
 */
@Service
public class WordLayerService {

    private static final Logger log = LoggerFactory.getLogger(WordLayerService.class);

    private static final String NS_DECL =
        "declare default element namespace 'http://religioustext.org/schema/words/1.0'; ";
    /** Ids and codes are interpolated into XQuery: accept only what the corpus actually uses. */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,60}");
    private static final long RETRY_MS = 60_000;
    private static final char MAQQEF = '־';

    /** One word: {@code after} is the printed marks that follow it (maqqef, paseq, sof pasuq,
     *  paragraph marks) including any leading space; {@code strongs} and {@code sense} are null
     *  when the source gives none (prefix-only words). */
    public record Word(String id, String text, String strongs, String sense, String after) {

        /** What the reader shows beside the word, e.g. {@code H1254a}; null for an unnumbered word. */
        public String label() {
            return strongs == null ? null : strongs + (sense == null ? "" : sense);
        }
    }

    /** One use of a Strong's number: where it is and the word as written there. */
    public record Occurrence(String book, int chapter, int verse, String text) {}

    private static final Pattern STRONGS = Pattern.compile("[HG]\\d{1,5}");

    private final BaseXProperties basex;
    private final RestTemplate http;
    private final String wordsDb;

    private final Map<String, Map<Integer, List<Word>>> chapters = new ConcurrentHashMap<>();
    private volatile Set<String> editions;
    private volatile long editionsAt;

    public WordLayerService(final BaseXProperties aBasex, final RestTemplate anHttp,
                            @Value("${basex.words-database:words}") final String aWordsDb) {
        this.basex = aBasex;
        this.http = anHttp;
        this.wordsDb = aWordsDb;
    }

    /** Whether the word layer holds words for this edition id (e.g. {@code bible-he-wlc}). */
    public boolean hasLayer(final String anEditionId) {
        return anEditionId != null && loadEditions().contains(anEditionId);
    }

    /** verse number -> words, for the verses of one chapter whose text matched exactly. Empty when
     *  there is no layer, no such chapter, or the database is unavailable. */
    public Map<Integer, List<Word>> forChapter(final String anEditionId, final String aBookCode,
                                               final int aChapter) {
        if (!hasLayer(anEditionId) || aBookCode == null
                || !SAFE.matcher(anEditionId).matches() || !SAFE.matcher(aBookCode).matches())
            return Map.of();
        return chapters.computeIfAbsent(anEditionId + "|" + aBookCode + "|" + aChapter, k -> {
            final String q = NS_DECL + "for $v in db:open('" + wordsDb + "')/words[@edition='" + anEditionId
                + "']/book[@code='" + aBookCode + "']/chapter[@number='" + aChapter
                + "']/verse[@align='exact'] for $w in $v/w return string-join(("
                + "string($v/@number), string($w/@id), string($w/@strongs), string($w/@sense),"
                + " string($w/@after), string($w)), '|')";
            return parse(query(q));
        });
    }

    /** How many words of this edition carry the Strong's number (0 if there is no layer). */
    public int countOccurrences(final String anEditionId, final String aStrongs) {
        if (!hasLayer(anEditionId) || !safe(anEditionId) || aStrongs == null || !STRONGS.matcher(aStrongs).matches())
            return 0;
        final List<String> r = query(NS_DECL + "count(db:open('" + wordsDb + "')/words[@edition='" + anEditionId
            + "']//w[@strongs='" + aStrongs + "'])");
        try { return r.isEmpty() ? 0 : Integer.parseInt(r.get(0).trim()); } catch (final NumberFormatException e) { return 0; }
    }

    /** The first {@code aLimit} uses of a Strong's number, in canonical order (empty if no layer). */
    public List<Occurrence> occurrences(final String anEditionId, final String aStrongs, final int aLimit) {
        if (!hasLayer(anEditionId) || !safe(anEditionId) || aStrongs == null
                || !STRONGS.matcher(aStrongs).matches() || aLimit < 1)
            return List.of();
        return parseOccurrences(query(NS_DECL + "for $w in (db:open('" + wordsDb + "')/words[@edition='"
            + anEditionId + "']//w[@strongs='" + aStrongs + "'])[position() <= " + Math.min(aLimit, 1000)
            + "] return string-join((string($w/ancestor::book/@code), string($w/ancestor::chapter/@number),"
            + " string($w/ancestor::verse/@number), string($w)), '|')"));
    }

    private static boolean safe(final String aValue) { return SAFE.matcher(aValue).matches(); }

    // ── Pure helpers (unit-tested) ────────────────────────────────────

    /** Rows of {@code book|chapter|verse|text}; a row that does not parse is skipped. */
    static List<Occurrence> parseOccurrences(final List<String> theRows) {
        final List<Occurrence> out = new ArrayList<>();
        for (final String row : theRows) {
            final String[] f = row.split("\\|", 4);
            if (f.length < 4) continue;
            try {
                out.add(new Occurrence(f[0], Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()), f[3]));
            } catch (final NumberFormatException e) { /* skip */ }
        }
        return List.copyOf(out);
    }


    /** Rows of {@code verse|id|strongs|sense|after|text} into words by verse, in order. A row that
     *  does not parse is skipped. */
    static Map<Integer, List<Word>> parse(final List<String> theRows) {
        final Map<Integer, List<Word>> out = new LinkedHashMap<>();
        for (final String row : theRows) {
            final String[] f = row.split("\\|", 6);
            if (f.length < 6) continue;
            final int verse;
            try { verse = Integer.parseInt(f[0].trim()); } catch (final NumberFormatException e) { continue; }
            out.computeIfAbsent(verse, k -> new ArrayList<>()).add(new Word(
                f[1], f[5], f[2].isEmpty() ? null : f[2], f[3].isEmpty() ? null : f[3], f[4]));
        }
        out.replaceAll((v, l) -> List.copyOf(l));
        return Collections.unmodifiableMap(out);
    }

    /** The verse as the corpus prints it: each word's text then its marks, a space between words
     *  unless the marks hold a maqqef. */
    public static String reconstruct(final List<Word> theWords) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < theWords.size(); i++) {
            final Word w = theWords.get(i);
            sb.append(w.text()).append(w.after());
            if (i < theWords.size() - 1 && w.after().indexOf(MAQQEF) < 0) sb.append(' ');
        }
        return sb.toString().strip();
    }

    /** The separator to put after word {@code anIndex} when laying words out one by one — the same
     *  rule as {@link #reconstruct(List)}: nothing after the last word or after a maqqef. */
    public static String gapAfter(final List<Word> theWords, final int anIndex) {
        return anIndex >= theWords.size() - 1 || theWords.get(anIndex).after().indexOf(MAQQEF) >= 0 ? "" : " ";
    }

    // ── BaseX ─────────────────────────────────────────────────────────

    private Set<String> loadEditions() {
        final Set<String> cached = editions;
        final long now = System.currentTimeMillis();
        if (cached != null && (!cached.isEmpty() || now - editionsAt < RETRY_MS)) return cached;
        final Set<String> found = new HashSet<>(query(NS_DECL + "db:open('" + wordsDb + "')/words/string(@edition)"));
        editions = Collections.unmodifiableSet(found);   // an empty answer is retried after a minute
        editionsAt = now;
        return editions;
    }

    private List<String> query(final String anXquery) {
        final List<String> results = new ArrayList<>();
        try {
            final URI uri = UriComponentsBuilder.fromHttpUrl(basex.uri() + "/" + wordsDb)
                .queryParam("query", anXquery).build(false).encode(StandardCharsets.UTF_8).toUri();
            final HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (basex.username() + ":" + basex.password()).getBytes(StandardCharsets.UTF_8)));
            headers.set("Accept", "text/plain");
            final ResponseEntity<String> r = http.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (r.getBody() != null && !r.getBody().isBlank())
                for (final String line : r.getBody().split("\n"))
                    if (!line.isBlank()) results.add(line.replace("\r", ""));
        } catch (final Exception e) {
            // No `words` database (not imported here) is normal, not an error worth a stack trace.
            log.debug("Word layer query failed: {}", e.getMessage());
        }
        return results;
    }
}
