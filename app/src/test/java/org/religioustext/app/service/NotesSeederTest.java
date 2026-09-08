// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure parts of translator-note seeding. The merge policy itself is
 * {@link DataSeeder#decideSeedAction} and is covered by {@link DataSeederTest}
 * — NotesSeeder deliberately reuses it rather than growing a second one.
 */
class NotesSeederTest {

    @Test
    void noteContentCarriesHeaderAnchorAndText() {
        assertThat(NotesSeeder.noteContent(
                "Lutherbibel 1912", "Anmerkung",
                "Das dritte Wasser heißt Hiddekel", "Tigris"))
            .isEqualTo("[Lutherbibel 1912 — Anmerkung]\n\n"
                + "»Das dritte Wasser heißt Hiddekel« — Tigris");
    }

    @Test
    void anchorlessNoteKeepsHeaderAndText() {
        // The converter always writes an anchor; a hand-edited ledger might not.
        assertThat(NotesSeeder.noteContent("Lutherbibel 1912", "Anmerkung", " ", "Tigris"))
            .isEqualTo("[Lutherbibel 1912 — Anmerkung]\n\nTigris");
        assertThat(NotesSeeder.noteContent("Lutherbibel 1912", "Anmerkung", null, "Tigris"))
            .isEqualTo("[Lutherbibel 1912 — Anmerkung]\n\nTigris");
    }

    @Test
    void editionSlugIsEmailSafe() {
        assertThat(NotesSeeder.editionSlug("Lutherbibel 1912")).isEqualTo("lutherbibel-1912");
        assertThat(NotesSeeder.editionSlug("  ")).isEqualTo("edition");
    }

    @Test
    void everyRegisteredEditionIsFullyConfigured() {
        // A half-filled EDITIONS entry would seed notes with a blank owner or an
        // unbindable sourceId — catch it here, not in the reader.
        NotesSeeder.EDITIONS.forEach((abbr, e) -> {
            assertThat(abbr).isNotBlank();
            assertThat(e.displayName()).isNotBlank();
            assertThat(e.noteLabel()).isNotBlank();
            assertThat(e.sourceId()).isNotBlank();
        });
    }
}
