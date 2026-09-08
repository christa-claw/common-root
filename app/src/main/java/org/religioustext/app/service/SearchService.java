// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.religioustext.app.config.MeiliConfig.MeiliProperties;
import org.religioustext.app.model.VerseRef;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side of full-text search. Queries the Meilisearch "search" index (one
 * index, two document kinds) and returns results GROUPED for the reader:
 * scripture verse-hits and comment-hits in separate buckets, each with its own
 * total so the UI can label "Verses (n) / Comments (m)" and rank each group
 * independently.
 *
 * One {@code /multi-search} call runs both bucket queries against the same index
 * with different {@code kind} filters, so it's a single network round-trip.
 *
 * The index is a DERIVED artifact rebuilt from BaseX + MySQL by
 * reindex_search.py; this service never writes to it.
 *
 * Reference vocabulary matches the rest of the app (session 14): a Bible verse
 * is (USFM code, chapter, verse); a Qur'an ayah carries the surah NUMBER as the
 * book code. So a hit's {@code ref} ("JHN.3.16") drops straight into the
 * reader's existing verse addressing for jump-to-verse.
 */
@Service
public class SearchService {

    /** Scripture document kinds, i.e. everything that is not a comment. */
    private static final List<String> SCRIPTURE_KINDS =
        List.of("bible", "quran", "hadith", "lds");

    private static final int DEFAULT_LIMIT = 20;

    private final RestTemplate http;
    private final MeiliProperties meili;

    public SearchService(final RestTemplate aHttp, final MeiliProperties aMeili) {
        this.http  = aHttp;
        this.meili = aMeili;
    }

    // ── result DTOs ───────────────────────────────────────────────────

    /** One scripture verse matched by the query. {@code ref} ("JHN.3.16") is the
     *  jump key into the reader; {@code snippet} is the match-highlighted text
     *  (Meilisearch {@code _formatted}, wrapping matches in &lt;mark&gt;), which
     *  the UI must render safely (escape the base text, allow only the mark
     *  tags). Falls back to plain text when no highlight is present. */
    public record VerseHit(String ref, String kind, String bookCode, String bookName,
                           int chapter, int verse, String text, String snippet,
                           boolean quran, String sourceId, String abbreviation,
                           String language) {}

    /** One comment matched by the query. {@code refs} are the verses it cites
     *  ("JHN.3.16"), so the UI can render each as a jump link; {@code authorType}
     *  is system|user. {@code snippet} carries highlighting, same caveat as
     *  {@link VerseHit#snippet()}. */
    public record CommentHit(String id, String content, String snippet,
                             String authorType, List<String> refs) {}

    /** Grouped results: each bucket plus its (estimated) total for count labels. */
    public record Results(String query,
                          List<VerseHit> verses, int verseTotal,
                          List<CommentHit> comments, int commentTotal) {
        public boolean isEmpty() { return verses.isEmpty() && comments.isEmpty(); }
    }

    // ── query ─────────────────────────────────────────────────────────

    /** Search both kinds, grouped, at the default per-bucket limit. */
    public Results search(final String aQuery) {
        return search(aQuery, DEFAULT_LIMIT);
    }

    /** Search both kinds, grouped, capping each bucket at {@code limitPerKind}.
     *  A blank query returns empty buckets (no call). */
    public Results search(final String aQuery, final int aLimitPerKind) {
        final String q = aQuery == null ? "" : aQuery.trim();
        if (q.isEmpty()) {
            return new Results(q, List.of(), 0, List.of(), 0);
        }

        final Map<String, Object> scriptureQuery = Map.of(
            "indexUid", meili.index(),
            "q", q,
            "limit", aLimitPerKind,
            "filter", "kind IN [" + String.join(", ", SCRIPTURE_KINDS) + "]",
            // Collapse to ONE hit per verse-ref: the same verse exists in many
            // editions, so without this the verse repeats once per translation.
            // The matched translations are fetched on demand by translationsFor()
            // when a collapsed result is opened.
            "distinct", "ref",
            "attributesToHighlight", List.of("text"),
            "highlightPreTag", "<mark>",
            "highlightPostTag", "</mark>");

        final Map<String, Object> commentQuery = Map.of(
            "indexUid", meili.index(),
            "q", q,
            "limit", aLimitPerKind,
            "filter", "kind = comment",
            "attributesToHighlight", List.of("content"),
            "highlightPreTag", "<mark>",
            "highlightPostTag", "</mark>");

        final JsonNode root = post("/multi-search",
            Map.of("queries", List.of(scriptureQuery, commentQuery)));
        final JsonNode results = root.path("results");
        final JsonNode scriptureRes = results.path(0);
        final JsonNode commentRes   = results.path(1);

        return new Results(q,
            parseVerses(scriptureRes.path("hits")),  estimatedTotal(scriptureRes),
            parseComments(commentRes.path("hits")),  estimatedTotal(commentRes));
    }

    /** The editions in which {@code query} matched a given verse {@code ref}
     *  ("JHN.3.16") — i.e. the translations the collapsed result folded together,
     *  best-ranked first. Opening a collapsed verse uses this: one match opens
     *  directly, several drive the translation picker. */
    public List<VerseHit> translationsFor(final String aQuery, final String aRef) {
        final String q = aQuery == null ? "" : aQuery.trim();
        if (q.isEmpty() || aRef == null || aRef.isBlank()) return List.of();
        final Map<String, Object> byRef = Map.of(
            "indexUid", meili.index(),
            "q", q,
            "limit", 50,
            "filter", "kind IN [" + String.join(", ", SCRIPTURE_KINDS)
                      + "] AND ref = \"" + aRef + "\"",
            "attributesToHighlight", List.of("text"),
            "highlightPreTag", "<mark>",
            "highlightPostTag", "</mark>");
        final JsonNode root = post("/multi-search", Map.of("queries", List.of(byRef)));
        return parseVerses(root.path("results").path(0).path("hits"));
    }

    // ── parsing ───────────────────────────────────────────────────────

    private List<VerseHit> parseVerses(final JsonNode theHits) {
        final List<VerseHit> out = new ArrayList<>();
        for (final JsonNode h : theHits) {
            final String bookCode = h.path("book_code").asText("");
            final String text     = VerseRef.clean(h.path("text").asText(""));
            out.add(new VerseHit(
                h.path("ref").asText(""),
                h.path("kind").asText(""),
                bookCode,
                h.path("book_name").asText(""),
                h.path("chapter").asInt(),
                h.path("verse").asInt(),
                text,
                h.path("_formatted").path("text").asText(text),
                isSurahCode(bookCode),
                h.path("source_id").asText(""),
                h.path("abbreviation").asText(""),
                h.path("language").asText("")));
        }
        return out;
    }

    private List<CommentHit> parseComments(final JsonNode theHits) {
        final List<CommentHit> out = new ArrayList<>();
        for (final JsonNode h : theHits) {
            final List<String> refs = new ArrayList<>();
            for (final JsonNode r : h.path("refs")) {
                refs.add(r.asText());
            }
            final String content = h.path("content").asText("");
            out.add(new CommentHit(
                h.path("id").asText(""),
                content,
                h.path("_formatted").path("content").asText(content),
                h.path("author_type").asText(""),
                refs));
        }
        return out;
    }

    /** Meilisearch returns {@code estimatedTotalHits} under the default (limit/
     *  offset) ranking — good enough for a "(n)" label. */
    private static int estimatedTotal(final JsonNode aResult) {
        return aResult.path("estimatedTotalHits").asInt(aResult.path("hits").size());
    }

    private JsonNode post(final String aPath, final Object aBody) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(meili.key());
        try {
            return http.exchange(meili.url() + aPath, HttpMethod.POST,
                new HttpEntity<>(aBody, headers), JsonNode.class).getBody();
        } catch (final RestClientException e) {
            throw new SearchUnavailableException(
                "Meilisearch query failed: " + e.getMessage(), e);
        }
    }

    /** Qur'an refs carry the surah NUMBER as the book code; Bible codes are
     *  alphanumeric USFM. */
    private static boolean isSurahCode(final String aBookCode) {
        return !aBookCode.isEmpty() && aBookCode.chars().allMatch(Character::isDigit);
    }

    /** Thrown when the index can't be reached or queried, so the UI can show a
     *  graceful "search unavailable" message rather than a stack trace. */
    public static class SearchUnavailableException extends RuntimeException {
        public SearchUnavailableException(final String aMessage, final Throwable aCause) {
            super(aMessage, aCause);
        }
    }
}
