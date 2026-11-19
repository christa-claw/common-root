// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.religioustext.app.api.ApiTextController.Span;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.LexiconService;
import org.religioustext.app.service.WordLayerService;
import org.religioustext.app.service.WordLayerService.Occurrence;
import org.religioustext.app.service.WordLayerService.Word;
import org.religioustext.app.ui.views.reader.SourceRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Strong's numbers through the API (docs/api-spec.md §3.9, docs/strongs-design.md):
 * <ul>
 *   <li>{@code GET /api/v1/texts/{src}/{ref}/words} — the words of a passage, each with its
 *       number, for the editions that have a word layer;</li>
 *   <li>{@code GET /api/v1/strongs/{id}} — the lexicon entry of a number;</li>
 *   <li>{@code GET /api/v1/strongs/{id}/occurrences} — where an edition uses it.</li>
 * </ul>
 *
 * <p>Words are text, so the word routes sit behind the same licence gate as the passage
 * routes. The lexicon is CC BY 4.0 reference data and the credit rides in every payload, as
 * the cross-references' does.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@RestController
@RequestMapping("/api/v1")
public class ApiStrongsController {

    /** Occurrences served when {@code limit} is absent. */
    static final int DEFAULT_LIMIT = 100;

    /** The most occurrences one call returns. */
    static final int MAX_LIMIT = 1000;

    private static final Pattern ID = Pattern.compile("[HG]\\d{1,5}");
    private static final String LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/";

    private final AppReadPolicy reads;
    private final ApiCorpusService corpus;
    private final WordLayerService words;
    private final LexiconService   lexicon;

    /**
     * @param anApiCorpusService resolves {@code src} to an edition
     * @param aWordLayerService  the words and their numbers
     * @param aLexiconService    the entries behind the numbers
     */
    public ApiStrongsController(final ApiCorpusService anApiCorpusService,
                                final WordLayerService aWordLayerService,
                                final LexiconService aLexiconService,
                                final AppReadPolicy anAppReadPolicy) {
        this.reads   = anAppReadPolicy;
        this.corpus  = anApiCorpusService;
        this.words   = aWordLayerService;
        this.lexicon = aLexiconService;
    }

    /**
     * {@code GET /api/v1/texts/{src}/{ref}/words?lang=fi} — the passage word by word. Only verses
     * whose words rebuild the edition's own text are served, so a verse may be absent from
     * {@code verses} although the passage route has it; a word the source gives no number
     * carries none. A numbered word carries the lexicon's short {@code gloss}, in the asked-for
     * language when there is a translation and in English otherwise, as {@link #entry} does.
     *
     * @param aSrc  the source token or document id
     * @param aRef  a link-format reference within one chapter
     * @param aLang a language tag for the glosses; unknown or absent means English
     * @return the words by verse, or an {@link ApiError}
     */
    @GetMapping(value = "/texts/{src}/{ref}/words", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> words(@PathVariable("src") final String aSrc,
                                        @PathVariable("ref") final String aRef,
                                        @RequestParam(value = "lang", required = false) final String aLang,
                                        final HttpServletRequest aRequest) {
        final Locale chosen = LocaleUtil.fromTag(aLang);
        final Locale locale = chosen == null ? LocaleUtil.EN : chosen;
        final SourceRow row = corpus.resolve(aSrc);
        final ResponseEntity<Object> refused = gate(aSrc, row, aRequest);
        if (refused != null) return refused;

        final Span span = Span.of(aRef);
        if (span == null) return error(400, "bad_ref",
            "Unrecognised reference '" + aRef + "'. Use link-format: JHN.1, JHN.1.1, PHP.4.10-13.");
        if (span.crossesChapters()) return error(400, "bad_ref",
            "Ranges must stay within one chapter in v1 (" + aRef + ").");

        final List<Map<String, Object>> verses = new ArrayList<>();
        final boolean[] hebrew = { false };      // the numbers' own prefix says whose data this is
        words.forChapter(row.id(), span.bookCode(), span.chapter()).forEach((number, list) -> {
            if (span.from() > 0 && (number < span.from() || number > span.to())) return;
            final List<Map<String, Object>> ws = new ArrayList<>();
            for (final Word w : list) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", w.id());
                m.put("text", w.text());
                if (w.strongs() != null) {
                    m.put("strongs", w.strongs());
                    if (w.strongs().startsWith("H")) hebrew[0] = true;
                    lexicon.entry(w.strongs().toUpperCase(Locale.ROOT), locale)
                        .map(LexiconService.Entry::gloss)
                        .filter(g -> !g.isBlank())
                        .ifPresent(g -> m.put("gloss", g));
                }
                if (w.sense() != null) m.put("sense", w.sense());
                m.put("after", w.after());
                ws.add(m);
            }
            final Map<String, Object> v = new LinkedHashMap<>();
            v.put("number", number);
            v.put("words", ws);
            verses.add(v);
        });
        if (verses.isEmpty()) return error(404, "no_such_passage",
            span.format() + " has no numbered words in " + row.abbreviation() + ".");

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", ApiTextController.token(row));
        body.put("direction", ApiTextController.direction(row));
        body.put("ref", span.format());
        body.put("book", span.bookCode());
        body.put("chapter", span.chapter());
        body.put("verses", verses);
        body.put("attribution", wordCredit(hebrew[0]));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    /**
     * {@code GET /api/v1/strongs/{id}?lang=fi} — one lexicon entry. The lemma and
     * transliteration are the source's; gloss and definition come in the asked-for language
     * when there is a translation (machine-made, and {@code machineTranslated} says so),
     * English otherwise.
     *
     * @param anId  {@code H} + number (Hebrew) or {@code G} + number (Greek), any case
     * @param aLang a language tag; unknown or absent means English
     * @return the entry, or 404 {@code no_such_entry}
     */
    @GetMapping(value = "/strongs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> entry(@PathVariable("id") final String anId,
                                        @RequestParam(value = "lang", required = false) final String aLang) {
        final String id = anId.toUpperCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) return error(400, "bad_strongs",
            "A Strong's number is H or G followed by digits (H430, G2424); got '" + anId + "'.");
        final Locale chosen = LocaleUtil.fromTag(aLang);
        final Locale locale = chosen == null ? LocaleUtil.EN : chosen;
        final LexiconService.Entry e = lexicon.entry(id, locale).orElse(null);
        if (e == null) return error(404, "no_such_entry", "No lexicon entry for " + id + ".");

        final boolean hebrew = id.charAt(0) == 'H';
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("strongs", e.strongs());
        body.put("lemma", e.lemma());
        body.put("lemmaLanguage", hebrew ? "he" : "grc");
        body.put("lemmaDirection", hebrew ? "rtl" : "ltr");
        body.put("transliteration", e.xlit());
        body.put("gloss", e.gloss());
        body.put("definition", e.definition());
        body.put("language", e.machineTranslated() ? locale.getLanguage() : "en");
        body.put("machineTranslated", e.machineTranslated());
        body.put("attribution", lexiconCredit(hebrew));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
            .body(body);
    }

    /**
     * {@code GET /api/v1/strongs/{id}/occurrences?src=wlc&limit=100} — where one edition uses
     * a number, in canonical order, with the word as written there.
     *
     * @param anId   the Strong's number, any case
     * @param aSrc   the edition to search; it must have a word layer
     * @param aLimit how many to return, 1 to {@value #MAX_LIMIT}; default {@value #DEFAULT_LIMIT}
     * @return the occurrences with the edition's total, or an {@link ApiError}
     */
    @GetMapping(value = "/strongs/{id}/occurrences", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> occurrences(@PathVariable("id") final String anId,
                                              @RequestParam("src") final String aSrc,
                                              @RequestParam(value = "limit", required = false) final Integer aLimit,
                                              final HttpServletRequest aRequest) {
        final String id = anId.toUpperCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) return error(400, "bad_strongs",
            "A Strong's number is H or G followed by digits (H430, G2424); got '" + anId + "'.");
        final int limit = aLimit == null ? DEFAULT_LIMIT : aLimit;
        if (limit < 1 || limit > MAX_LIMIT) return error(400, "bad_limit",
            "limit must be between 1 and " + MAX_LIMIT + ".");
        final SourceRow row = corpus.resolve(aSrc);
        final ResponseEntity<Object> refused = gate(aSrc, row, aRequest);
        if (refused != null) return refused;

        final int total = words.countOccurrences(row.id(), id);
        final List<Map<String, Object>> out = new ArrayList<>();
        if (total > 0) {
            for (final Occurrence o : words.occurrences(row.id(), id, limit)) {
                final Map<String, Object> m = new LinkedHashMap<>();
                m.put("ref", o.book() + "." + o.chapter() + "." + o.verse());
                m.put("word", o.text());
                out.add(m);
            }
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("strongs", id);
        body.put("text", ApiTextController.token(row));
        body.put("total", total);
        body.put("occurrences", out);
        body.put("truncated", total > out.size());
        body.put("attribution", wordCredit(id.charAt(0) == 'H'));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    // ── Gate, credits, errors ──────────────────────────────────────────────

    /** The refusal for an edition the word routes cannot serve, or {@code null} when they can. */
    private ResponseEntity<Object> gate(final String aSrc, final SourceRow aRow, final HttpServletRequest aRequest) {
        if (aRow == null) return error(404, "unknown_source",
            "No edition '" + aSrc + "'. See /api/v1/texts for tokens.");
        if (!words.hasLayer(aRow.id())) return error(404, "no_word_layer",
            aRow.abbreviation() + " has no Strong's numbers. They exist for original-language "
            + "editions only.");
        final AppReadPolicy.Access access = reads.access(aRow, aRequest);
        if (access == AppReadPolicy.Access.REFUSED || access == AppReadPolicy.Access.SPENT) {
            return reads.refusal(aRow, access);
        }
        return null;
    }

    /** The credit for the numbers on the words: OSHB for Hebrew ({@code H}), TAGNT for Greek. */
    static Map<String, String> wordCredit(final boolean theHebrewFlag) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (theHebrewFlag) {
            m.put("source", "Open Scriptures Hebrew Bible Project");
            m.put("url", "https://github.com/openscriptures/morphhb");
            m.put("changes", "Augmented lemmas normalised to H#### plus sense; word text unchanged.");
        } else {
            m.put("source", "STEPBible TAGNT");
            m.put("url", "https://github.com/STEPBible/STEPBible-Data");
            m.put("changes", "Numbers attached to the edition's own words; word text unchanged.");
        }
        m.put("licence", "CC BY 4.0");
        m.put("licenceUrl", LICENCE_URL);
        return m;
    }

    static Map<String, String> lexiconCredit(final boolean theHebrewFlag) {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("source", theHebrewFlag ? "Open Scriptures Hebrew Lexicon" : "STEPBible TBESG");
        m.put("url", theHebrewFlag ? "https://github.com/openscriptures/HebrewLexicon"
                                   : "https://github.com/STEPBible/STEPBible-Data");
        m.put("licence", "CC BY 4.0");
        m.put("licenceUrl", LICENCE_URL);
        m.put("changes", "Glosses and definitions machine-translated where served in another language.");
        return m;
    }

    private static ResponseEntity<Object> error(final int aStatus, final String aSlug, final String aMessage) {
        return ResponseEntity.status(aStatus).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(aStatus, aSlug, aMessage, "strongs"));
    }
}
