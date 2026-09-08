// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views.reader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reader's source catalogue: the {@code List<String[]>} from
 * {@code TextQueryService.listSources()} plus the lookups the view needs over it.
 *
 * Collects the by-id / by-abbreviation / primary-vs-translation queries that were
 * open-coded as repeated for-loops and stream filters throughout ReaderView, each
 * re-deriving the field positions by hand. Rows are returned raw ({@code String[]})
 * so the existing Vaadin combo boxes keep working unchanged during the migration;
 * the field decoding goes through {@link SourceRow}.
 *
 * <p>Every method mirrors the exact behaviour of the ReaderView helper it replaces,
 * including the null / short-row guards (an unknown id or a row too short to carry
 * a type yields null / false / excluded, never an exception). Pinned by
 * {@code SourceCatalogTest}.
 *
 * <p>Pattern: repository / collection wrapper over the raw source rows.
 */
public final class SourceCatalog {

    private final List<String[]> rows;

    public SourceCatalog(final List<String[]> aRows) {
        this.rows = aRows != null ? aRows : List.of();
    }

    /** The raw rows, in catalogue order. */
    public List<String[]> rows() { return rows; }

    /** Full source row for an id, or null. Mirrors {@code ReaderView.sourceById}. */
    public String[] byId(final String anId) {
        if (anId == null) return null;
        for (final String[] row : rows)
            if (row.length > 0 && anId.equals(row[0])) return row;
        return null;
    }

    /** Row whose @abbreviation matches the token (case-insensitive), or null.
     *  Mirrors {@code ReaderView.sourceByToken}. */
    public String[] byToken(final String aToken) {
        if (aToken == null) return null;
        for (final String[] row : rows)
            if (row.length > 2 && aToken.equalsIgnoreCase(row[2])) return row;
        return null;
    }

    /** Whether the source with this id is a Qur'an text. Mirrors
     *  {@code ReaderView.isQuranSource}: an id miss (or a row too short to carry a
     *  type) yields false. */
    public boolean isQuran(final String aSourceId) {
        for (final String[] row : rows)
            if (row.length > 6 && row[0].equals(aSourceId)) return "quran".equals(row[6]);
        return false;
    }

    /** Primary (directly-selectable) sources: Bibles + Qur'an/hadith bases,
     *  excluding companion translations. Mirrors {@code ReaderView.primarySources}. */
    public List<String[]> primaries() {
        return rows.stream().filter(r -> SourceRow.of(r).isPrimary()).toList();
    }

    /** One lineage rung: a source row plus whether the link is ATTESTED (a
     *  recorded {@code @basedOn} parent, rendered plain) or a stand-in
     *  original-language WITNESS supplied by the floor (rendered marked —
     *  shown because every translation stands on the Hebrew/Greek, but NOT a
     *  claim that this exact text was the edition's base). */
    public record Rung(String[] row, boolean attested) { }

    /**
     * Antecedent generations for an edition: generation 0 = its direct parents
     * ({@code @basedOn}, ordered main-line first), generation 1 = their parents,
     * and so on up the family tree. Each text appears once, at its SHALLOWEST
     * generation — diamonds collapse and cycles terminate, because a claimed id
     * is never revisited.
     *
     * <p>Every BIBLE edition additionally gets an ORIGINAL-LANGUAGE FLOOR: a
     * final generation holding the corpus's original-language witnesses
     * ({@code @original} rows — WLC/Hebrew, TR/Greek) that the recorded chain
     * didn't already reach, since every translation ultimately stands on the
     * Hebrew and the Greek. Verse-level pruning shows each verse exactly the
     * witness that covers it. Editions that ARE originals get no floor; a
     * chain already ending at the originals is unchanged. Empty when the id is
     * unknown or the edition is a lineage root itself.
     *
     * @param anEditionId the edition whose ancestry to walk (nullable)
     * @return one row-list per generation, nearest first; never null
     */
    public List<List<Rung>> lineageGenerationsOf(final String anEditionId) {
        final List<List<Rung>> generations = new ArrayList<>();
        if (anEditionId == null) return generations;
        final Set<String> seen = new HashSet<>();
        seen.add(anEditionId);
        List<Rung> frontier = parentsOf(anEditionId, seen);
        while (!frontier.isEmpty()) {
            generations.add(frontier);
            final List<Rung> next = new ArrayList<>();
            for (final Rung rung : frontier)
                next.addAll(parentsOf(rung.row().length > 0 ? rung.row()[0] : null, seen));
            frontier = next;
        }
        final SourceRow edition = SourceRow.of(byId(anEditionId));
        if (edition != null && edition.isBible() && !edition.isOriginal()) {
            final List<Rung> floor = new ArrayList<>();
            for (final String[] row : rows) {
                final SourceRow s = SourceRow.of(row);
                if (s.isOriginal() && s.id() != null && seen.add(s.id()))
                    floor.add(new Rung(row, false));
            }
            if (!floor.isEmpty()) generations.add(floor);
        }
        return generations;
    }

    /** The direct parents of an edition — its {@code @basedOn} ids resolved to
     *  rows in stored order, unknown ids skipped — excluding any id already
     *  claimed by a shallower generation via {@code theSeen} (which this method
     *  extends as it claims). */
    private List<Rung> parentsOf(final String anId, final Set<String> theSeen) {
        final String[] row = byId(anId);
        if (row == null) return List.of();
        final String basedOn = SourceRow.of(row).basedOn();
        if (basedOn == null || basedOn.isBlank()) return List.of();
        final List<Rung> out = new ArrayList<>();
        for (final String pid : basedOn.split(",")) {
            final String id = pid.trim();
            if (id.isEmpty() || !theSeen.add(id)) continue;
            final String[] parent = byId(id);
            if (parent != null) out.add(new Rung(parent, true));
        }
        return out;
    }

    /**
     * Descendant generations for an edition: generation 0 = the editions that
     * name this one in their own {@code @basedOn}, generation 1 = their
     * descendants, and so on down the family tree. The mirror of
     * {@link #lineageGenerationsOf} and it behaves the same way — each text
     * appears once, at its SHALLOWEST generation, so diamonds collapse and
     * cycles terminate.
     *
     * <h4>Attested links only — the floor is NOT inverted</h4>
     * Walking upwards, a Bible is also shown an original-language WITNESS it may
     * never have used ({@code Rung.attested() == false}), marked as such,
     * because every translation ultimately stands on the Hebrew and the Greek.
     * That is an honest hedge in one direction and a falsehood in the other:
     * inverted, it would make the WLC the parent of nearly the whole corpus and
     * assert of each descendant that it derives from this text. So there is no
     * descendant floor, and every rung returned here is attested — which is why
     * callers never need the marked rendering on this side.
     *
     * @param anEditionId the edition whose descendants to walk (nullable)
     * @return one row-list per generation, nearest first; never null, and empty
     *         for an unknown id or an edition nothing is based on
     */
    public List<List<Rung>> descendantGenerationsOf(final String anEditionId) {
        final List<List<Rung>> generations = new ArrayList<>();
        if (anEditionId == null || byId(anEditionId) == null) return generations;
        final Set<String> seen = new HashSet<>();
        seen.add(anEditionId);
        List<Rung> frontier = childrenOf(anEditionId, seen);
        while (!frontier.isEmpty()) {
            generations.add(frontier);
            final List<Rung> next = new ArrayList<>();
            for (final Rung rung : frontier)
                next.addAll(childrenOf(rung.row().length > 0 ? rung.row()[0] : null, seen));
            frontier = next;
        }
        return generations;
    }

    /** The editions that name {@code anId} among their {@code @basedOn} parents,
     *  in catalog order, excluding any already claimed by a shallower generation
     *  via {@code theSeen} (which this method extends as it claims). */
    private List<Rung> childrenOf(final String anId, final Set<String> theSeen) {
        if (anId == null) return List.of();
        final List<Rung> out = new ArrayList<>();
        for (final String[] row : rows) {
            final SourceRow child = SourceRow.of(row);
            final String basedOn = child.basedOn();
            if (basedOn == null || basedOn.isBlank()) continue;
            boolean namesIt = false;
            for (final String pid : basedOn.split(",")) {
                if (anId.equals(pid.trim())) { namesIt = true; break; }
            }
            if (!namesIt) continue;
            final String id = child.id();
            if (id == null || !theSeen.add(id)) continue;
            out.add(new Rung(row, true));
        }
        return out;
    }

    /** Companion translations attached to a base text — the Qur'an/hadith
     *  translations whose @baseText is {@code baseId}. Mirrors the loops in
     *  {@code setCompanion} and {@code translationFlags}. Empty when baseId is null. */
    public List<String[]> translationsOf(final String aBaseId) {
        if (aBaseId == null) return List.of();
        return rows.stream().filter(r -> {
            final SourceRow s = SourceRow.of(r);
            return s.hasCompanions() && aBaseId.equals(s.baseText());
        }).toList();
    }
}
