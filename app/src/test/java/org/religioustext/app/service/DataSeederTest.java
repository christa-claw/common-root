package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.DataSeeder.SeedAction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.religioustext.app.service.DataSeeder.decideSeedAction;

/**
 * The V14 edit-aware reseed policy — the invariant behind "channel corrections survive
 * restarts": a human edit always outranks the ledger, and a deliberate delete stays dead.
 */
class DataSeederTest {

    @Test
    void newEntryIsInserted() {
        assertThat(decideSeedAction(false, false, false)).isEqualTo(SeedAction.INSERT);
    }

    @Test
    void unEditedRowIsUpdatedFromTheLedger() {
        assertThat(decideSeedAction(true, false, false)).isEqualTo(SeedAction.UPDATE);
    }

    @Test
    void editedRowIsPreservedVerbatim() {
        assertThat(decideSeedAction(true, true, false)).isEqualTo(SeedAction.PRESERVE);
    }

    @Test
    void tombstoneAlwaysWins() {
        // A deleted comment must never resurrect — regardless of row state.
        assertThat(decideSeedAction(false, false, true)).isEqualTo(SeedAction.SKIP_TOMBSTONED);
        assertThat(decideSeedAction(true,  false, true)).isEqualTo(SeedAction.SKIP_TOMBSTONED);
        assertThat(decideSeedAction(true,  true,  true)).isEqualTo(SeedAction.SKIP_TOMBSTONED);
    }
}
