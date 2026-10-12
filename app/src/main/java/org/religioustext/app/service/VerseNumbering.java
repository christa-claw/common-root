// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Finds the SAME verse in another Bible edition from a verse of the Hebrew text — the stopgap behind
 * the Strong's window's translation line (the full verse map is cross-references-design.md
 * milestone 5, and replaces this).
 *
 * <p>The Hebrew text (WLC) and the KJV number some verses differently (Gen 32:1 is KJV 31:55; the
 * Psalm titles; Joel; Malachi). OSHB's {@code VerseMap.xml} lists all 1,978 differences
 * ({@code versification/wlc-kjv-versemap.xml}, CC BY 4.0). Which numbering an edition follows is
 * in {@code versification/schemes.properties}:
 * <ul>
 *   <li>{@code kjv} — English numbering: the Hebrew reference goes through the map;</li>
 *   <li>{@code hebrew} — the Hebrew numbering itself: the reference is used unchanged;</li>
 *   <li>not listed — unknown: no answer. Never a guess, because a verse from the wrong place under
 *       a Hebrew word is worse than no translation.</li>
 * </ul>
 * An edition that follows English numbering overall may still split or merge verses inside a few
 * chapters ({@code versification/chapter-differences.properties}, generated from the corpus's verse
 * counts). For a verse in such a chapter the answer is "none" too — a missing translation, never a
 * verse one off.
 *
 * <p>A {@code partial} map entry (one verse starting inside another; 7 of them) maps to the KJV verse
 * it falls in, so that answer is approximate by nature.
 */
@Service
public class VerseNumbering {

    /** A verse address in USFM terms. */
    public record Ref(String book, int chapter, int verse) {}

    public enum Scheme { KJV, HEBREW, UNKNOWN }

    private final Map<String, Ref> hebrewToKjv;
    private final Map<String, Scheme> schemes;
    /** edition id -> "BOOK.chapter" entries whose verse count differs from the KJV's. */
    private final Map<String, Set<String>> differingChapters;

    public VerseNumbering() {
        this("/versification/wlc-kjv-versemap.xml", "/versification/schemes.properties",
             "/versification/chapter-differences.properties");
    }

    VerseNumbering(final String aMapResource, final String aSchemesResource) {
        this(aMapResource, aSchemesResource, "/versification/none.properties");
    }

    VerseNumbering(final String aMapResource, final String aSchemesResource, final String aDifferencesResource) {
        this.hebrewToKjv = loadMap(aMapResource);
        this.schemes = loadSchemes(aSchemesResource);
        this.differingChapters = loadDifferences(aDifferencesResource);
    }

    public Scheme schemeOf(final String anEditionId) {
        return schemes.getOrDefault(anEditionId, Scheme.UNKNOWN);
    }

    /** Where this edition has the verse that the Hebrew text numbers {@code book chapter:verse}; empty
     *  when the edition's numbering is unknown. */
    public Optional<Ref> fromHebrew(final String anEditionId, final String aBook, final int aChapter, final int aVerse) {
        final Ref hebrew = new Ref(aBook, aChapter, aVerse);
        return switch (schemeOf(anEditionId)) {
            case HEBREW -> Optional.of(hebrew);
            // without the map a KJV answer would be the unmapped Hebrew reference — the wrong verse for ~2,000
            // of them, silently. No map, no answer.
            case KJV -> hebrewToKjv.isEmpty() ? Optional.empty()
                : Optional.of(hebrewToKjv.getOrDefault(key(aBook, aChapter, aVerse), hebrew))
                    .filter(r -> !differs(anEditionId, r));
            case UNKNOWN -> Optional.empty();
        };
    }

    /** Whether this edition counts the chapter's verses differently from the KJV (so verse numbers in it
     *  may sit one off). */
    private boolean differs(final String anEditionId, final Ref aKjvRef) {
        return differingChapters.getOrDefault(anEditionId, Set.of()).contains(aKjvRef.book() + "." + aKjvRef.chapter());
    }

    // ── Loading ───────────────────────────────────────────────────────

    private static Map<String, Set<String>> loadDifferences(final String aResource) {
        final Properties p = new Properties();
        try (InputStream in = VerseNumbering.class.getResourceAsStream(aResource)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            return Map.of();
        }
        final Map<String, Set<String>> out = new HashMap<>();
        for (final String id : p.stringPropertyNames())
            out.put(id, Set.copyOf(new HashSet<>(Arrays.asList(p.getProperty(id).trim().split("\\s*,\\s*")))));
        return Map.copyOf(out);
    }

    private static String key(final String aBook, final int aChapter, final int aVerse) {
        return aBook + "|" + aChapter + "|" + aVerse;
    }

    private static Map<String, Ref> loadMap(final String aResource) {
        final Map<String, Ref> out = new HashMap<>();
        try (InputStream in = VerseNumbering.class.getResourceAsStream(aResource)) {
            if (in == null) return Map.of();
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no entities: the file is ours, but be strict
            final NodeList verses = f.newDocumentBuilder().parse(in).getElementsByTagName("verse");
            for (int i = 0; i < verses.getLength(); i++) {
                final Element e = (Element) verses.item(i);
                final Ref wlc = parse(e.getAttribute("wlc")), kjv = parse(e.getAttribute("kjv"));
                // a partial verse can appear twice (a and b); the first answer stands
                if (wlc != null && kjv != null) out.putIfAbsent(key(wlc.book(), wlc.chapter(), wlc.verse()), kjv);
            }
        } catch (final Exception e) {
            return Map.of();   // no map: KJV-numbered editions then get no answer (see fromHebrew)
        }
        return Map.copyOf(out);
    }

    /** {@code Gen.32.1} or {@code 1Kgs.18.34!a} (OSIS, with an optional part marker) as a USFM ref. */
    static Ref parse(final String anOsis) {
        if (anOsis == null || anOsis.isBlank()) return null;
        final String[] p = anOsis.split("!")[0].split("\\.");
        if (p.length != 3) return null;
        final String usfm = XrefSeeder.USFM_BY_OSIS.get(p[0]);
        if (usfm == null) return null;
        try {
            return new Ref(usfm, Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Scheme> loadSchemes(final String aResource) {
        final Properties p = new Properties();
        try (InputStream in = VerseNumbering.class.getResourceAsStream(aResource)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            return Map.of();
        }
        final Map<String, Scheme> out = new HashMap<>();
        for (final String id : p.stringPropertyNames()) {
            final String v = p.getProperty(id).trim();
            if ("kjv".equals(v)) out.put(id, Scheme.KJV);
            else if ("hebrew".equals(v)) out.put(id, Scheme.HEBREW);
        }
        return Map.copyOf(out);
    }
}
