// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.api.ApiAccountController.Position;
import org.religioustext.app.api.ApiAccountController.PrefsIn;
import org.religioustext.app.model.user.UserPreferences;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ApiAccountController}: how the shared preferences map onto the website's row. */
class ApiAccountControllerTest {

    private static PrefsIn only(final Position aPosition) {
        return new PrefsIn(null, null, null, null, null, null, aPosition);
    }

    @Test
    void aPutChangesOnlyWhatItCarries() {
        final UserPreferences row = new UserPreferences();
        row.setDefaultSource("niv");
        row.setShowXrefMarkers(true);
        ApiAccountController.apply(row, new PrefsIn("fi", null, true, null, null, null, null));
        assertThat(row.getUiLanguage()).isEqualTo("fi");
        assertThat(row.isShowCommentMarkers()).isTrue();
        assertThat(row.getDefaultSource()).isEqualTo("niv");
        assertThat(row.isShowXrefMarkers()).isTrue();
    }

    @Test
    void meShowsTheEmailOnlyWhenThereIsNoName() {
        assertThat(ApiAccountController.meView("Ruth", "r@test.local"))
            .containsEntry("name", "Ruth").containsEntry("displayName", "Ruth");
        assertThat(ApiAccountController.meView(null, "r@test.local"))
            .containsEntry("name", "r@test.local").containsEntry("displayName", "");
        assertThat(ApiAccountController.meView("  ", "r@test.local"))
            .containsEntry("name", "r@test.local").containsEntry("displayName", "");
    }

    @Test
    void aDisplayNameMustBePresentAndFitTheColumn() {
        assertThat(ApiAccountController.problemWith(new ApiAccountController.MeIn(null))).isNotNull();
        assertThat(ApiAccountController.problemWith(new ApiAccountController.MeIn(""))).isNull();
        assertThat(ApiAccountController.problemWith(new ApiAccountController.MeIn("x".repeat(100)))).isNull();
        assertThat(ApiAccountController.problemWith(new ApiAccountController.MeIn("x".repeat(101)))).isNotNull();
        // surrounding spaces are trimmed before storing, so they do not count
        assertThat(ApiAccountController.problemWith(new ApiAccountController.MeIn(" " + "x".repeat(100) + " "))).isNull();
    }

    @Test
    void anEmptyStringClears() {
        final UserPreferences row = new UserPreferences();
        row.setUiLanguage("fi");
        ApiAccountController.apply(row, new PrefsIn("", null, null, null, null, null, null));
        assertThat(row.getUiLanguage()).isNull();
    }

    @Test
    void thePositionIsStoredAsAReaderLinkAndReadBackAsItsFirstColumn() {
        final UserPreferences row = new UserPreferences();
        ApiAccountController.apply(row, only(new Position("kjv", "JHN.3")));
        assertThat(row.getLastPosition()).isEqualTo("c1.src=kjv&c1.ref=JHN.3");
        assertThat(ApiAccountController.position("c1.src=KJV&c1.ref=JHN.3.16&c2.src=q-en"))
            .isEqualTo(Map.of("src", "kjv", "ref", "JHN.3.16"));
        assertThat(ApiAccountController.position("cols=2")).isNull();
    }

    @Test
    void resumeOffForgetsThePositionAndIgnoresANewOne() {
        final UserPreferences row = new UserPreferences();
        row.setLastPosition("c1.src=kjv&c1.ref=JHN.3");
        ApiAccountController.apply(row, new PrefsIn(null, null, null, null, null, false, new Position("kjv", "GEN.1")));
        assertThat(row.isResumeEnabled()).isFalse();
        assertThat(row.getLastPosition()).isNull();
        assertThat(ApiAccountController.view(row).get("position")).isNull();
    }

    @Test
    void mutedVoicesRoundTrip() {
        final UserPreferences row = new UserPreferences();
        ApiAccountController.apply(row, new PrefsIn(null, null, null, null, List.of("A", " B ", "A"), null, null));
        assertThat(ApiAccountController.view(row).get("mutedVoices")).isEqualTo(List.of("A", "B"));
    }

    @Test
    void aReaderWhoNeverSavedHasNoUpdatedAt() {
        // the app reads a missing updatedAt as "nothing to take from the website yet"
        assertThat(ApiAccountController.view(null)).containsEntry("updatedAt", null);
    }

    @Test
    void valuesTheWebsiteWouldNotAcceptAreRefused() {
        assertThat(ApiAccountController.problemWith(new PrefsIn("xx", null, null, null, null, null, null))).isNotNull();
        assertThat(ApiAccountController.problemWith(new PrefsIn("fi", "kjv", null, null, null, null, null))).isNull();
        assertThat(ApiAccountController.problemWith(only(new Position("kjv", "JHN.3&c2.src=x")))).isNotNull();
        assertThat(ApiAccountController.problemWith(only(new Position("kjv", "Q.2.255")))).isNull();
    }
}
