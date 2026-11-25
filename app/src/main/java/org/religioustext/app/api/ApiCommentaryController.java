// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.religioustext.app.web.CorpusDownloadController;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@code GET /api/v1/commentaries/{book}/{chapter}/{verse}} — what every
 * commentary in the corpus says about one verse (docs/api-spec.md, "Commentaries"):
 * the phone app's Commentary tab. Shaped like the cross-reference route — a USFM
 * book code, then numbers — because a commentary is read against whichever Bible
 * the reader has open, not as an edition of its own.
 *
 * <p>A commentary verse is prose, not a line of scripture, so the comments come as
 * paragraphs of plain runs ({@link CommentaryJson}), never as markup. A comment
 * covering a range (John 3:14–17) answers for every verse in it and says so in
 * {@code from}/{@code to}. On a chapter's first verse the chapter's introductions
 * come too, and on 1:1 the book's.
 *
 * <p>Only openly licensed commentaries are served: public domain or CC0, every part
 * of a combined licence ("CC0; Public Domain"). The ingest already loads nothing
 * else; this check keeps a later licensed one off the API until it is decided for.
 *
 * <p>Numbering is the commentary's own, which for the corpus's Christian works is
 * the KJV's and for the Jewish ones the Hebrew Bible's; where those differ
 * (Malachi 4, some Psalm superscriptions) a verse finds the comment stored under
 * its number in each system.
 *
 * @author Christa Claw
 * @version 1.1.0
 * @since 1.1.0
 */
@RestController
@RequestMapping("/api/v1")
public class ApiCommentaryController {

    private static final Pattern BOOK = Pattern.compile("[0-9A-Z]{3}");

    private final ApiCorpusService corpus;

    /**
     * @param anApiCorpusService the corpus reads: the query, and the catalogue for each commentary's metadata
     */
    public ApiCommentaryController(final ApiCorpusService anApiCorpusService) {
        this.corpus = anApiCorpusService;
    }

    /**
     * @param aBook    USFM code, any case
     * @param aChapter chapter, 1 or more
     * @param aVerse   verse, 1 or more
     * @return the commentaries on the verse, by title; a verse none comments on is 200 with an empty list
     */
    @GetMapping(value = "/commentaries/{book}/{chapter}/{verse}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> verse(@PathVariable("book") final String aBook,
                                        @PathVariable("chapter") final int aChapter,
                                        @PathVariable("verse") final int aVerse) {
        final String book = aBook.toUpperCase(Locale.ROOT);
        if (!BOOK.matcher(book).matches()) return error(404, "no_such_book",
            "No book '" + aBook + "'. Use a USFM code such as GEN or 1CO.");
        if (aChapter < 1 || aVerse < 1) return error(400, "bad_ref", "Chapter and verse must be 1 or more.");

        final String xml = corpus.commentariesOn(book, aChapter, aVerse);
        if (xml == null) return error(503, "corpus_unavailable", "The corpus could not be read; try again shortly.");

        final List<Map<String, Object>> out = new ArrayList<>();
        for (final CommentaryJson.Found f : CommentaryJson.read(xml, book)) {
            final SourceRow row = corpus.resolve(f.id());
            if (row == null || !open(row.license())) continue;
            out.add(entry(row, f));
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("title")), String.CASE_INSENSITIVE_ORDER));

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("ref", book + "." + aChapter + "." + aVerse);
        body.put("count", out.size());
        body.put("commentaries", out);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(body);
    }

    /**
     * Whether a licence lets the text leave the platform: every {@code ;}-separated
     * part is public domain (as the download gate reads it) or CC0.
     *
     * @param aLicense the commentary's {@code @license}
     * @return {@code true} only when every part is open
     */
    static boolean open(final String aLicense) {
        if (aLicense == null || aLicense.isBlank()) return false;
        for (final String part : aLicense.split(";")) {
            final String p = part.trim();
            if (p.isEmpty()) continue;
            if (!p.toUpperCase(Locale.ROOT).startsWith("CC0") && !CorpusDownloadController.isPublicDomain(p))
                return false;
        }
        return true;
    }

    private static Map<String, Object> entry(final SourceRow aRow, final CommentaryJson.Found aFound) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", ApiTextController.token(aRow));
        m.put("abbreviation", aRow.abbreviation());
        m.put("title", aRow.name());
        if (aFound.author() != null && !aFound.author().isBlank()) m.put("author", aFound.author());
        m.put("language", aRow.language());
        m.put("direction", ApiTextController.direction(aRow));
        m.put("licence", aRow.license());
        if (!aFound.intros().isEmpty()) m.put("intros", aFound.intros());
        m.put("comments", aFound.comments());
        return m;
    }

    private static ResponseEntity<Object> error(final int aStatus, final String aSlug, final String aMessage) {
        return ResponseEntity.status(aStatus).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(aStatus, aSlug, aMessage, "commentaries"));
    }
}
