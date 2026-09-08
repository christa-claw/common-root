// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views.reader;

/**
 * Typed, read-only view over one row of {@code TextQueryService.listSources()}.
 *
 * The reader receives its source catalogue as {@code List<String[]>}, each row a
 * fixed 9-field record. Decoding it positionally ({@code row[6]} for the type,
 * {@code r.length>8 && r[8]} for the base text, {@code first[3]} for direction, …)
 * had spread across the whole of ReaderView; this class is the single place that
 * knows the field order, so a schema change touches one file and every read is
 * intention-revealing.
 *
 * <p>The field layout:
 * <pre>
 *   [0] id            [3] direction (rtl/ltr)   [6] type (bible/quran/hadith)
 *   [1] name          [4] license               [7] language
 *   [2] abbreviation  [5] source (origin/url)   [8] baseText (companion base id)
 *   [9] basedOn — comma-separated antecedent ids (translation lineage)
 *   [10] original — "true" on original-language witnesses (WLC, TR)
 *   [11] year — publication / codex year for rung chips ("" when unknown)
 * </pre>
 *
 * <p>Every accessor is length-guarded to match the exact behaviour the scattered
 * inline checks had (a short/truncated row must never throw): a field past the
 * array's end reads as {@code null}, except {@link #type()} and {@link #language()}
 * which read as {@code ""} — mirroring the {@code r.length>6 ? r[6] : ""} form the
 * call sites used for those two. A field that is present but {@code null} is
 * returned as-is, exactly as the old positional reads did.
 *
 * <p>Pattern: typed wrapper over a positional array. Behaviour is pinned by
 * {@code SourceRowTest} against the pre-refactor ReaderView semantics.
 */
public final class SourceRow {

    // Field positions in a listSources() row — the ONLY place these live.
    private static final int ID = 0, NAME = 1, ABBREVIATION = 2, DIRECTION = 3,
                             LICENSE = 4, SOURCE = 5, TYPE = 6, LANGUAGE = 7, BASE_TEXT = 8,
                             BASED_ON = 9, ORIGINAL = 10, YEAR = 11;

    private final String[] raw;

    private SourceRow(final String[] aRaw) { this.raw = aRaw; }

    /** Wrap a raw source row. The array is used as-is (not copied); these rows are
     *  already treated as immutable everywhere. Returns {@code null} for a {@code
     *  null} array so callers can chain {@code SourceRow.of(catalog.byId(x))}. */
    public static SourceRow of(final String[] aRaw) {
        return aRaw == null ? null : new SourceRow(aRaw);
    }

    /** raw[i] when the index exists (even if the stored value is null), else the default —
     *  exactly the {@code length > i ? raw[i] : dflt} form the old call sites used. */
    private String at(final int anIndex, final String aDefault) {
        return anIndex < raw.length ? raw[anIndex] : aDefault;
    }
    private String at(final int anIndex) { return at(anIndex, null); }

    public String id()           { return at(ID); }
    public String name()         { return at(NAME); }
    public String abbreviation() { return at(ABBREVIATION); }
    public String direction()    { return at(DIRECTION); }
    public String license()      { return at(LICENSE); }
    public String source()       { return at(SOURCE); }
    public String type()         { return at(TYPE, ""); }
    public String language()     { return at(LANGUAGE, ""); }
    public String baseText()     { return at(BASE_TEXT); }

    /** Comma-separated ids of the editions this one stands on (its antecedent
     *  translations, main line first), or {@code ""} — the lineage the reader's
     *  rung controls walk. Field absent on rows predating the lineage stamp. */
    public String basedOn()      { return at(BASED_ON, ""); }

    /** Whether this row is an original-language witness (stamped {@code
     *  @original} — WLC for the Hebrew, TR for the Greek): the texts that form
     *  every Bible edition's lineage FLOOR. */
    public boolean isOriginal()  { return "true".equalsIgnoreCase(at(ORIGINAL, "")); }

    /** Publication (or codex) year for rung chips, or {@code ""} when unknown
     *  — chips degrade to the bare abbreviation. */
    public String year()         { return at(YEAR, ""); }

    /** The backing array — for the call sites still handing raw rows to Vaadin
     *  components while the migration off {@code String[]} is in progress. */
    public String[] raw()        { return raw; }

    public boolean isRtl()   { return "rtl".equalsIgnoreCase(direction()); }
    public boolean isBible() { return "bible".equals(type()); }
    public boolean isQuran() { return "quran".equals(type()); }

    /** Text types that carry a paired companion translation shown beneath the
     *  primary (Qur'an + hadith). Mirrors {@code ReaderView.hasCompanions(type)}. */
    public boolean hasCompanions() {
        return "quran".equals(type()) || "hadith".equals(type());
    }

    /** True when this row is a companion <em>translation</em> — a Qur'an/hadith
     *  text with a non-blank {@code baseText} pointing at its Arabic base — rather
     *  than a directly-selectable primary source. Mirrors the {@code
     *  primarySources()} exclusion exactly (including its implicit {@code length>8}
     *  guard, since {@link #baseText()} is null for shorter rows). */
    public boolean isTranslation() {
        final String base = baseText();
        return hasCompanions() && base != null && !base.isBlank();
    }

    /** A directly-selectable primary source (Bibles + Qur'an/hadith bases). */
    public boolean isPrimary() { return !isTranslation(); }
}
