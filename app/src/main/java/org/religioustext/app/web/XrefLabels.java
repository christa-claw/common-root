// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.service.CrossRefQueryService.XRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

/**
 * A cross reference as the margin of a printed book writes it: "John 1:1–3", "Heb 11:3",
 * "Ps 89:11–90:2". The abbreviations come from {@code print/xref-names.properties}, the same
 * file {@code scripts/print/xrefs.py} reads for the PDF, so the order page's preview and the
 * book agree.
 */
public final class XrefLabels {

    private static final Map<String, String> NAMES = load();

    private XrefLabels() { }

    public static String label(final XRef aRef) {
        final String book = NAMES.getOrDefault(aRef.toBook(), aRef.toBook());
        final String start = book + " " + aRef.toChapter() + ":" + aRef.toVerse();
        if (aRef.toEndChapter() == null || aRef.toEndVerse() == null) return start;
        if (aRef.toEndChapter() == aRef.toChapter()) {
            return aRef.toEndVerse() == aRef.toVerse() ? start : start + "–" + aRef.toEndVerse();
        }
        return start + "–" + aRef.toEndChapter() + ":" + aRef.toEndVerse();
    }

    /** The abbreviation for a USFM code, or null. */
    static String name(final String aCode) { return NAMES.get(aCode); }

    private static Map<String, String> load() {
        final Properties p = new Properties();
        try (InputStream in = XrefLabels.class.getResourceAsStream("/print/xref-names.properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException ignored) {
            // Without the file the margin falls back to the bare book code.
        }
        final java.util.HashMap<String, String> m = new java.util.HashMap<>();
        p.forEach((k, v) -> m.put((String) k, (String) v));
        return Map.copyOf(m);
    }
}
