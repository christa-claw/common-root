// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.TextQueryService;
import org.religioustext.app.ui.views.reader.BookTitles;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The text the print designer's preview pages are set from: a handful of real
 * passages of the edition being ordered, so the preview shows the book as it
 * would print instead of a stand-in. Only the editions in {@link PrintEditions}
 * are served.
 *
 * <p>The passages are the ones the preview's pages need: the opening of the
 * Bible, a book boundary in each order, and the closing page of each order. The
 * page declares which verses of them go on which page; this just hands over the
 * chapters, with the book names in the edition's own language and, for English,
 * the traditional long titles.
 */
@RestController
public class DesignerSampleController {

    private record Ref(String code, int chapter) { }

    private static final List<Ref> REFS = List.of(
        new Ref("GEN", 1), new Ref("GEN", 2), new Ref("GEN", 50), new Ref("EXO", 1),
        new Ref("DEU", 34), new Ref("PSA", 90), new Ref("JOS", 1),
        new Ref("AMO", 1), new Ref("AMO", 2), new Ref("NUM", 36), new Ref("MAL", 1),
        new Ref("REV", 22), new Ref("2PE", 3));

    private final TextQueryService text;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public DesignerSampleController(final TextQueryService aText) {
        this.text = aText;
    }

    @GetMapping(value = "/designer/sample", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sample(@RequestParam("source") final String aSource) {
        final PrintEditions.Edition ed = PrintEditions.byAbbr(aSource).orElse(null);
        if (ed == null) return ResponseEntity.notFound().build();
        final Map<String, Object> body = cache.computeIfAbsent(ed.abbr(), k -> build(ed));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(body);
    }

    private Map<String, Object> build(final PrintEditions.Edition anEdition) {
        final Properties base = read("/i18n/booknames.properties");
        final Properties edLang = read("/i18n/booknames_" + anEdition.lang() + ".properties");
        final Properties own = edLang.isEmpty() ? base : edLang;

        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, String> longTitles = new LinkedHashMap<>();
        final Map<String, List<Object[]>> chapters = new LinkedHashMap<>();
        final Map<String, String> titles = new LinkedHashMap<>();
        for (final Ref r : REFS) {
            names.computeIfAbsent(r.code(), c -> own.getProperty(c, base.getProperty(c, c)));
            if ("en".equals(anEdition.lang())) {
                final String t = BookTitles.longTitle(base.getProperty(r.code()));
                if (t != null) longTitles.putIfAbsent(r.code(), t);
            }
            final List<VerseRef> verses =
                text.versesByCodeChapter(anEdition.id(), r.code(), r.chapter());
            final List<Object[]> rows = new ArrayList<>();
            for (final VerseRef v : verses) {
                if (v.getContent() != null && !v.getContent().isBlank())
                    rows.add(new Object[] {v.getVerseNumber(), v.getContent().strip()});
            }
            final String key = r.code() + "." + r.chapter();
            chapters.put(key, rows);
            if (!verses.isEmpty() && verses.get(0).getChapterTitle() != null
                    && !verses.get(0).getChapterTitle().isBlank())
                titles.put(key, verses.get(0).getChapterTitle().strip());
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("abbr", anEdition.abbr());
        out.put("lang", anEdition.lang());
        out.put("names", names);
        out.put("long", longTitles);
        out.put("titles", titles);
        out.put("chapters", chapters);
        return out;
    }

    private static Properties read(final String aPath) {
        final Properties p = new Properties();
        try (InputStream in = DesignerSampleController.class.getResourceAsStream(aPath)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException ignored) {
            // A missing bundle falls back to English names.
        }
        return p;
    }
}
