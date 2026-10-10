// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.religioustext.app.util.TypedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

/**
 * Seeds the {@code cross_reference} table from the OpenBible.info download
 * shipped in the jar ({@code crossrefs/openbible-cross-references.zip}, CC BY
 * 4.0 — see {@code SOURCE.md} beside it).
 *
 * <p>Gated by the file's SHA-256 ({@code cross_reference_seed}): an unchanged
 * file costs one query at boot. When the file does change, rows are upserted
 * on the natural key, so an existing row KEEPS its {@code xrf-} id and only has
 * its votes (and range end) refreshed; only unseen references mint an id.
 * Stable ids matter because the API and reader-contributed references (see
 * {@code docs/logged-in.md}) will point at them.
 *
 * <p>Rows with negative votes are never stored (the read side would drop them
 * anyway); if a reference that was stored turns negative in a later file it is
 * deleted by natural key. References that simply vanish from a later file are
 * not swept — OpenBible does not retire references, it re-votes them.
 */
@Service
public class XrefSeeder {

    private static final Logger log = LoggerFactory.getLogger(XrefSeeder.class);

    static final String SOURCE = "openbible";
    private static final String RESOURCE = "crossrefs/openbible-cross-references.zip";
    private static final int CHUNK = 500;

    /** OpenBible's (OSIS) book codes -> the app's USFM codes (CommentReference.bookCode). */
    static final Map<String, String> USFM_BY_OSIS = osisToUsfm();

    /** True for one of the 66 Protestant-canon USFM codes the references are keyed by. */
    public static boolean isBookCode(final String aCode) {
        return aCode != null && USFM_BY_OSIS.containsValue(aCode);
    }

    /** One parsed row. {@code endChapter}/{@code endVerse} are null unless the target is a range. */
    record Xref(String fromBook, int fromChapter, int fromVerse,
                String toBook, int toChapter, int toVerse,
                Integer toEndChapter, Integer toEndVerse, int votes) {}

    /** Parsed file plus what was set aside, for the boot log. */
    record Parsed(List<Xref> rows, int negative, int malformed) {}

    private final JdbcTemplate jdbc;
    private final CrossRefQueryService queries;

    public XrefSeeder(final JdbcTemplate aJdbc, final CrossRefQueryService aQueries) {
        this.jdbc = aJdbc;
        this.queries = aQueries;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(160)
    public void seed() {
        try {
            final byte[] zip = readResource();
            if (zip == null) {
                log.info("No {} on the classpath — no cross-references to seed.", RESOURCE);
                return;
            }
            final String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(zip));
            final List<String> stored = jdbc.queryForList(
                "SELECT sha256 FROM cross_reference_seed WHERE source = ?", String.class, SOURCE);
            if (!stored.isEmpty() && stored.get(0).equals(sha)) {
                log.info("Cross-references ({}) unchanged (sha256 {}…) — skipping seed.",
                    SOURCE, sha.substring(0, 12));
                return;
            }
            final Parsed parsed = parse(unzipFirstEntry(zip));
            log.info("Seeding {} cross-references from {} ({} negative-vote, {} malformed set aside).",
                parsed.rows().size(), SOURCE, parsed.negative(), parsed.malformed());
            upsert(parsed.rows());
            jdbc.update("INSERT INTO cross_reference_seed (source, sha256, row_count) VALUES (?,?,?) "
                + "ON DUPLICATE KEY UPDATE sha256 = VALUES(sha256), row_count = VALUES(row_count), "
                + "seeded_at = CURRENT_TIMESTAMP", SOURCE, sha, parsed.rows().size());
            queries.clearCache();   // a chapter read before the seed finished must not stay empty
        } catch (final Exception e) {
            // Reference data is optional apparatus: never block the reader booting.
            log.error("Cross-reference seeding failed; the reader runs without them.", e);
        }
    }

    private void upsert(final List<Xref> rows) {
        final String head = "INSERT INTO cross_reference (id, source, from_book, from_chapter, "
            + "from_verse, to_book, to_chapter, to_verse, to_end_chapter, to_end_verse, votes) VALUES ";
        final String tail = " ON DUPLICATE KEY UPDATE votes = VALUES(votes), "
            + "to_end_chapter = VALUES(to_end_chapter), to_end_verse = VALUES(to_end_verse)";   // id NOT updated
        final String one = "(?,?,?,?,?,?,?,?,?,?,?)";
        // Multi-row statements: a statement per row took ~15 minutes for the full file.
        for (int i = 0; i < rows.size(); i += CHUNK) {
            final List<Xref> chunk = rows.subList(i, Math.min(i + CHUNK, rows.size()));
            final Object[] args = new Object[chunk.size() * 11];
            int n = 0;
            for (final Xref x : chunk) {
                args[n++] = TypedId.generate(TypedId.Type.CROSS_REF);
                args[n++] = SOURCE;
                args[n++] = x.fromBook();
                args[n++] = x.fromChapter();
                args[n++] = x.fromVerse();
                args[n++] = x.toBook();
                args[n++] = x.toChapter();
                args[n++] = x.toVerse();
                args[n++] = x.toEndChapter();
                args[n++] = x.toEndVerse();
                args[n++] = x.votes();
            }
            jdbc.update(head + String.join(",", java.util.Collections.nCopies(chunk.size(), one)) + tail, args);
        }
    }

    // ── Pure parsing (unit-tested) ────────────────────────────────────

    /** Parse OpenBible's tab-separated file. Negative votes and malformed rows are counted, not kept. */
    static Parsed parse(final Reader aReader) throws IOException {
        final List<Xref> rows = new ArrayList<>();
        int negative = 0, malformed = 0;
        final BufferedReader in = new BufferedReader(aReader);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank() || line.startsWith("From Verse")) continue;
            final String[] f = line.split("\t");
            if (f.length < 3) { malformed++; continue; }
            final int[] from = verse(f[0]);
            final String fromBook = usfm(f[0]);
            final String[] target = f[1].split("-", 2);
            final int[] to = verse(target[0]);
            final String toBook = usfm(target[0]);
            final int[] rawEnd = target.length == 2 ? verse(target[1]) : null;
            final String endBook = target.length == 2 ? usfm(target[1]) : null;
            final boolean endOk = target.length == 1 || (rawEnd != null && endBook != null);
            // A range that runs from one book into the next (2Chr 36:22 – Ezra 1:3) has no
            // room for a foreign end in this schema; the start verse is still a valid
            // reference, so keep that and drop only the end.
            final int[] end = rawEnd != null && toBook != null && toBook.equals(endBook) ? rawEnd : null;
            final Integer votes = integer(f[2]);
            if (from == null || fromBook == null || to == null || toBook == null
                    || votes == null || !endOk) {
                malformed++;
                continue;
            }
            if (votes < 0) { negative++; continue; }
            rows.add(new Xref(fromBook, from[0], from[1], toBook, to[0], to[1],
                end == null ? null : end[0], end == null ? null : end[1], votes));
        }
        return new Parsed(rows, negative, malformed);
    }

    /** {@code "Gen.1.1"} -> {1,1}; null if not Book.chapter.verse with positive numbers. */
    private static int[] verse(final String aRef) {
        final String[] p = aRef.trim().split("\\.");
        if (p.length != 3) return null;
        final Integer c = integer(p[1]), v = integer(p[2]);
        return c == null || v == null || c < 1 || v < 1 ? null : new int[] {c, v};
    }

    private static String usfm(final String aRef) {
        return USFM_BY_OSIS.get(aRef.trim().split("\\.")[0]);
    }

    private static Integer integer(final String s) {
        try { return Integer.valueOf(s.trim()); } catch (final NumberFormatException e) { return null; }
    }

    private static Map<String, String> osisToUsfm() {
        final String[] osis = {"Gen","Exod","Lev","Num","Deut","Josh","Judg","Ruth","1Sam","2Sam",
            "1Kgs","2Kgs","1Chr","2Chr","Ezra","Neh","Esth","Job","Ps","Prov","Eccl","Song","Isa",
            "Jer","Lam","Ezek","Dan","Hos","Joel","Amos","Obad","Jonah","Mic","Nah","Hab","Zeph",
            "Hag","Zech","Mal","Matt","Mark","Luke","John","Acts","Rom","1Cor","2Cor","Gal","Eph",
            "Phil","Col","1Thess","2Thess","1Tim","2Tim","Titus","Phlm","Heb","Jas","1Pet","2Pet",
            "1John","2John","3John","Jude","Rev"};
        final String[] usfm = {"GEN","EXO","LEV","NUM","DEU","JOS","JDG","RUT","1SA","2SA","1KI",
            "2KI","1CH","2CH","EZR","NEH","EST","JOB","PSA","PRO","ECC","SNG","ISA","JER","LAM",
            "EZK","DAN","HOS","JOL","AMO","OBA","JON","MIC","NAM","HAB","ZEP","HAG","ZEC","MAL",
            "MAT","MRK","LUK","JHN","ACT","ROM","1CO","2CO","GAL","EPH","PHP","COL","1TH","2TH",
            "1TI","2TI","TIT","PHM","HEB","JAS","1PE","2PE","1JN","2JN","3JN","JUD","REV"};
        final Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < osis.length; i++) m.put(osis[i], usfm[i]);
        return Map.copyOf(m);
    }

    private static byte[] readResource() throws IOException {
        final ClassPathResource r = new ClassPathResource(RESOURCE);
        if (!r.exists()) return null;
        try (InputStream in = r.getInputStream()) { return in.readAllBytes(); }
    }

    private static Reader unzipFirstEntry(final byte[] aZip) throws IOException {
        final ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(aZip));
        if (zin.getNextEntry() == null) throw new IOException("empty zip: " + RESOURCE);
        return new InputStreamReader(zin, StandardCharsets.UTF_8);
    }
}
