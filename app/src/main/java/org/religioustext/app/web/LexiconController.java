// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.i18n.TranslationProvider;
import org.religioustext.app.service.LexiconService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /lexicon/hebrew/{strongs}?lang=fi} and {@code /lexicon/greek/{strongs}?lang=fi} — one
 * Strong's entry (an {@code H} id and a {@code G} id respectively) for the reader's tooltip.
 *
 * <p>Public, like the reader itself (reading requires no account), and not part of the keyed
 * {@code /api/v1} surface: it exists for the page's own script, which fetches an entry only when a
 * number is hovered or tapped. The text is static, so responses are publicly cacheable for a day.
 * The CC BY credit travels in the payload so the tooltip can show it.
 */
@RestController
public class LexiconController {

    private static final String HEBREW_CREDIT_URL = "https://github.com/openscriptures/HebrewLexicon";
    private static final String GREEK_CREDIT_URL = "https://github.com/STEPBible/STEPBible-Data";
    private static final String LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/";

    private final LexiconService lexicon;
    private final TranslationProvider translations;

    public LexiconController(final LexiconService aLexicon, final TranslationProvider aTranslations) {
        this.lexicon = aLexicon;
        this.translations = aTranslations;
    }

    @GetMapping(value = "/lexicon/hebrew/{strongs}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> hebrew(@PathVariable("strongs") final String aStrongs,
                                                      @RequestParam(value = "lang", defaultValue = "en") final String aLang) {
        return entry('H', aStrongs, aLang);
    }

    @GetMapping(value = "/lexicon/greek/{strongs}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> greek(@PathVariable("strongs") final String aStrongs,
                                                     @RequestParam(value = "lang", defaultValue = "en") final String aLang) {
        return entry('G', aStrongs, aLang);
    }

    private ResponseEntity<Map<String, Object>> entry(final char aPrefix, final String aStrongs, final String aLang) {
        final Locale chosen = LocaleUtil.fromTag(aLang);
        final Locale locale = chosen == null ? LocaleUtil.EN : chosen;
        final String id = aStrongs.toUpperCase(Locale.ROOT);
        if (id.isEmpty() || id.charAt(0) != aPrefix) return ResponseEntity.notFound().build();   // /greek/ serves only G ids
        final boolean hebrew = aPrefix == 'H';
        return lexicon.entry(id, locale).map(e -> {
            final Map<String, Object> body = new LinkedHashMap<>();
            body.put("strongs", e.strongs());
            body.put("lemma", e.lemma());
            body.put("xlit", e.xlit());
            body.put("gloss", e.gloss());
            body.put("definition", e.definition());
            // right-to-left locales read the tooltip right to left; the lemma's own script sets its direction
            body.put("dir", "ar".equals(locale.getLanguage()) || "he".equals(locale.getLanguage()) ? "rtl" : "ltr");
            body.put("lemmaLang", hebrew ? "he" : "grc");
            body.put("lemmaDir", hebrew ? "rtl" : "ltr");
            body.put("note", e.machineTranslated() ? translations.getTranslation("reader.strongs.machine", locale) : "");
            body.put("hint", translations.getTranslation("reader.strongs.more", locale));   // "Double-click for more"
            body.put("credit", Map.of(
                "text", hebrew ? "Open Scriptures Hebrew Lexicon · CC BY 4.0" : "STEPBible TBESG · CC BY 4.0",
                "url", hebrew ? HEBREW_CREDIT_URL : GREEK_CREDIT_URL, "licenceUrl", LICENCE_URL));
            return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic()).body(body);
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
