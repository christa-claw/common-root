// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.religioustext.app.service.CrossRefQueryService;
import org.religioustext.app.service.CrossRefQueryService.XRef;
import org.religioustext.app.service.XrefSeeder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/v1/crossrefs/{book}/{chapter}} — the verse-to-verse
 * cross-references of one chapter (docs/api-spec.md §3.8).
 *
 * <p>Vocabulary is the reader's: {@code {book}} is a USFM code
 * ({@code JHN}) and every reference in the payload is link-format
 * ({@code ROM.5.8}, {@code PRO.8.22-30}) — the same string the reader's
 * {@code ref=} parameter takes. Anchors are the canonical (KJV) numbering.
 *
 * <p>Key auth and the request counter come from {@link ApiAuthFilter}, as for
 * every {@code /api/v1} route. The data changes only when a deploy re-seeds it,
 * so responses are publicly cacheable for an hour.
 *
 * <p>The CC BY 4.0 attribution OpenBible.info requires rides in EVERY payload,
 * so a consumer that stores or forwards the references carries the credit
 * with them.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiCrossRefController {

    static final Map<String, String> ATTRIBUTION = attribution();

    private final CrossRefQueryService xrefs;

    public ApiCrossRefController(final CrossRefQueryService aXrefs) { this.xrefs = aXrefs; }

    /**
     * @param aBook    USFM code, any case ({@code jhn} is {@code JHN})
     * @param aChapter chapter number, 1 or more
     * @return the chapter's references by verse, most-voted first; a chapter without any is
     *         200 with an empty {@code verses}
     */
    @GetMapping(value = "/crossrefs/{book}/{chapter}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> chapter(@PathVariable("book") final String aBook,
                                          @PathVariable("chapter") final int aChapter) {
        final String book = aBook.toUpperCase(Locale.ROOT);
        if (!XrefSeeder.isBookCode(book)) return error(404, "no_such_book",
            "No cross-references for '" + aBook + "'. Use a Protestant-canon USFM code such as GEN or 1CO.");
        if (aChapter < 1) return error(400, "bad_ref", "Chapter must be 1 or more.");

        final Map<String, List<Map<String, Object>>> verses = new LinkedHashMap<>();
        xrefs.forChapter(book, aChapter).forEach((verse, list) -> {
            final List<Map<String, Object>> out = new ArrayList<>();
            for (final XRef x : list) out.add(entry(x));
            verses.put(String.valueOf(verse), out);
        });

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("book", book);
        body.put("chapter", aChapter);
        body.put("numbering", "KJV");
        body.put("verses", verses);
        body.put("attribution", ATTRIBUTION);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    static Map<String, Object> entry(final XRef x) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", x.id());
        m.put("ref", x.linkRef());
        m.put("votes", x.votes());
        return m;
    }

    private static ResponseEntity<Object> error(final int aStatus, final String aSlug, final String aMessage) {
        return ResponseEntity.status(aStatus).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(aStatus, aSlug, aMessage, "crossrefs"));
    }

    private static Map<String, String> attribution() {
        final Map<String, String> m = new LinkedHashMap<>();
        m.put("source", "OpenBible.info");
        m.put("url", "https://www.openbible.info/labs/cross-references/");
        m.put("licence", "CC BY 4.0");
        m.put("licenceUrl", "https://creativecommons.org/licenses/by/4.0/");
        m.put("changes", "References with negative votes removed; ranges that cross into another "
            + "book shortened to their first verse; books recoded to USFM.");
        return java.util.Collections.unmodifiableMap(m);
    }
}
