// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.ui.views.EditionInfo;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/texts/{src}/about} — an edition's description, the same
 * prose as its {@code /edition/:abbr} info page on the site: who made it, when,
 * from what, and the facts strip. The phone app shows it beside the edition
 * picker, so a reader can learn what they are reading without leaving the app.
 *
 * <p>The content is {@link EditionInfo#PAGES}, read as-is: no second copy, so a
 * correction to an info page reaches the app with the next deploy. A language
 * with no translated page answers with the English one, and says so in
 * {@code lang} — the same fallback the site's own links use
 * ({@link EditionInfo#slugFor}).
 *
 * <p>The prose carries {@code [[ABBR|name]]} links to other editions on the
 * site. Those are site addresses, so the API gives the display text only.
 *
 * @author Christa Claw
 * @version 1.0.2-SNAPSHOT
 * @since 1.0.2
 */
@RestController
@RequestMapping("/api/v1")
public class ApiEditionInfoController {

    private final ApiCorpusService corpus;

    /**
     * @param anApiCorpusService resolves the token to its edition
     */
    public ApiEditionInfoController(final ApiCorpusService anApiCorpusService) {
        this.corpus = anApiCorpusService;
    }

    /**
     * The edition's description in the asked-for language, else English.
     *
     * @param aSrc  the source token or document id
     * @param aLang optional BCP 47 tag ({@code fi}, {@code zh-Hant}); English when absent
     * @return the description, or 404 when the edition is unknown or has no info page
     */
    @GetMapping(value = "/texts/{src}/about", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> about(@PathVariable("src") final String aSrc,
                                        @RequestParam(value = "lang", required = false) final String aLang) {
        final SourceRow row = corpus.resolve(aSrc);
        if (row == null) {
            return notFound("unknown_source", "No edition '" + aSrc + "'. See /api/v1/texts for tokens.");
        }
        final EditionInfo page = pageFor(row.abbreviation(), aLang);
        if (page == null) {
            return notFound("no_description", row.abbreviation() + " has no description yet.");
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(view(ApiTextController.token(row), page));
    }

    private static ResponseEntity<Object> notFound(final String aSlug, final String aMessage) {
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(404, aSlug, aMessage, "texts"));
    }

    /**
     * Whether an edition has a description at all, for the catalogue's
     * {@code about} flag, so a client offers it only where it exists.
     *
     * @param anAbbr the edition abbreviation, any case, may be {@code null}
     * @return true when an English page exists
     */
    static boolean hasPage(final String anAbbr) {
        return pageKey(anAbbr) != null;
    }

    /** The reader-link marker of a page written for an Arabic text read with
     *  its English companion beneath it. */
    private static final String COMPANION = "companion=1";

    /**
     * The info page an edition is described by: its own, or — for a hadith
     * collection, stored as two texts ({@code ABD-AR}, {@code ABD-EN}) — the one
     * page the site writes for the pair ({@code ABD}). The pair is recognised by
     * the page's own reader link opening the Arabic with its companion, not by
     * the suffix alone, so {@code Q-AR} and {@code Q-EN} keep their separate pages.
     *
     * @param anAbbr the edition abbreviation, any case, may be {@code null}
     * @return the English page's key, or {@code null} when there is none
     */
    static String pageKey(final String anAbbr) {
        if (anAbbr == null) return null;
        final String abbr = anAbbr.toUpperCase(Locale.ROOT);
        if (EditionInfo.PAGES.containsKey(abbr)) return abbr;
        final int dash = abbr.lastIndexOf('-');
        if (dash <= 0) return null;
        final String pair = abbr.substring(0, dash);
        final String suffix = abbr.substring(dash + 1);
        final EditionInfo page = EditionInfo.PAGES.get(pair);
        final boolean companion = page != null && page.readerHref != null
            && page.readerHref.contains(COMPANION) && ("AR".equals(suffix) || "EN".equals(suffix));
        return companion ? pair : null;
    }

    /**
     * The page for an edition in a language, falling back to English.
     *
     * @param anAbbr the edition abbreviation, any case, may be {@code null}
     * @param aLang  a BCP 47 tag, or {@code null}
     * @return the page, or {@code null} when the edition has none
     */
    static EditionInfo pageFor(final String anAbbr, final String aLang) {
        final String abbr = pageKey(anAbbr);
        if (abbr == null) return null;
        // the language subtag only: the site writes one Chinese page, and zh-Hant reads it
        final String lang = aLang == null || aLang.isBlank()
            ? "en" : Locale.forLanguageTag(aLang).getLanguage();
        return EditionInfo.PAGES.get(EditionInfo.slugFor(abbr, lang));
    }

    /**
     * Prose with its {@code [[ABBR|name]]} links to other editions reduced to
     * the name: the links are site addresses, which the app has no use for.
     *
     * @param aText the page's prose
     * @param aLang the page's language
     * @return the text as a reader sees it
     */
    static String plain(final String aText, final String aLang) {
        final StringBuilder out = new StringBuilder();
        for (final EditionInfo.FactPart part : EditionInfo.parseFact(aText, aLang)) {
            out.append(part.text());
        }
        return out.toString();
    }

    /**
     * The JSON shape: heading, summary, paragraphs, facts and the site page.
     *
     * @param aToken the edition token the client asked with
     * @param aPage  the page
     * @return the body
     */
    static Map<String, Object> view(final String aToken, final EditionInfo aPage) {
        final List<Map<String, String>> facts = new ArrayList<>();
        for (final Map.Entry<String, String> f : aPage.facts.entrySet()) {
            final Map<String, String> fact = new LinkedHashMap<>();
            fact.put("label", f.getKey());
            fact.put("value", plain(f.getValue(), aPage.lang));
            facts.add(fact);
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", aToken);
        m.put("lang", aPage.lang);
        m.put("heading", aPage.heroHeading);
        m.put("summary", aPage.description);
        m.put("paragraphs", Arrays.stream(aPage.heroBody.split("\n\n"))
            .filter(p -> !p.isBlank()).map(p -> plain(p, aPage.lang)).toList());
        m.put("facts", facts);
        if (aPage.thanks != null) m.put("thanks", plain(aPage.thanks, aPage.lang));
        m.put("page", EditionInfo.PREFIX + aPage.slug);
        return m;
    }
}
