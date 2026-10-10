// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read side of cross-references: the related verses cited from each verse of a
 * (bookCode, chapter), shaped for the reader's badge and dialog.
 *
 * <p>Anchors are USFM code + chapter + verse in the canonical (KJV) numbering,
 * edition-independent like comments. The data only changes when
 * {@link XrefSeeder} runs at boot, so a chapter is read once and cached; the
 * seeder calls {@link #clearCache()} after it writes.
 */
@Service
public class CrossRefQueryService {

    /** One reference as shown under a verse. {@code id} is the stable {@code xrf-} id;
     *  {@code toEndChapter}/{@code toEndVerse} are null unless the target is a range
     *  such as Prov 8:22–30. */
    public record XRef(String id, String toBook, int toChapter, int toVerse,
                       Integer toEndChapter, Integer toEndVerse, int votes) {

        /** The target in link-format (docs/link-format.md): {@code ROM.5.8},
         *  {@code PRO.8.22-30}, {@code PSA.89.11-90.2}. */
        public String linkRef() {
            final String start = toBook + "." + toChapter + "." + toVerse;
            if (toEndChapter == null || toEndVerse == null) return start;
            if (toEndChapter == toChapter) return toEndVerse == toVerse ? start : start + "-" + toEndVerse;
            return start + "-" + toEndChapter + "." + toEndVerse;
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<String, Map<Integer, List<XRef>>> cache = new ConcurrentHashMap<>();

    public CrossRefQueryService(final JdbcTemplate aJdbc) { this.jdbc = aJdbc; }

    /** verse number -> its references, most-voted first; verses without any are absent. */
    public Map<Integer, List<XRef>> forChapter(final String aBook, final int aChapter) {
        return cache.computeIfAbsent(aBook + "/" + aChapter, k -> shape(jdbc.query(
            "SELECT from_verse, id, to_book, to_chapter, to_verse, to_end_chapter, to_end_verse, votes "
                + "FROM cross_reference WHERE from_book = ? AND from_chapter = ?",
            (rs, i) -> new Row(rs.getInt("from_verse"),
                new XRef(rs.getString("id"), rs.getString("to_book"), rs.getInt("to_chapter"), rs.getInt("to_verse"),
                    (Integer) rs.getObject("to_end_chapter"), (Integer) rs.getObject("to_end_verse"),
                    rs.getInt("votes"))),
            aBook, aChapter)));
    }

    public void clearCache() { cache.clear(); }

    record Row(int fromVerse, XRef ref) {}

    /** Drop negative-vote rows, group by verse, order by votes descending. The
     *  tie-break on the target keeps the order stable between requests. */
    static Map<Integer, List<XRef>> shape(final List<Row> aRows) {
        final Comparator<XRef> order = Comparator.comparingInt(XRef::votes).reversed()
            .thenComparing(XRef::toBook).thenComparingInt(XRef::toChapter)
            .thenComparingInt(XRef::toVerse);
        final Map<Integer, List<XRef>> byVerse = new LinkedHashMap<>();
        aRows.stream()
            .filter(r -> r.ref().votes() >= 0)
            .sorted(Comparator.comparingInt(Row::fromVerse))
            .forEach(r -> byVerse.computeIfAbsent(r.fromVerse(), v -> new java.util.ArrayList<>())
                .add(r.ref()));
        byVerse.values().forEach(l -> l.sort(order));
        byVerse.replaceAll((v, l) -> List.copyOf(l));
        return java.util.Collections.unmodifiableMap(byVerse);
    }
}
