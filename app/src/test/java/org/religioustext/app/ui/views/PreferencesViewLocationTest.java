// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "current location" line on the Preferences page: the saved position (a
 * reader-link query string) spelled out per column, book names from the
 * booknames bundle, Qur'an refs as surah:ayah, junk ignored.
 */
class PreferencesViewLocationTest {

    @Test
    void oneLinePerColumnWithLocalisedBookName() {
        final List<String> where = PreferencesView.describePosition(
            "cols=2&c1.src=kjv&c1.ref=GEN.12.3&c1.order=canon&c2.src=web&c2.ref=GEN.12.3");
        assertThat(where).containsExactly("KJV · Genesis 12:3", "WEB · Genesis 12:3");
    }

    @Test
    void chapterOnlyAndQuran() {
        assertThat(PreferencesView.describePosition("c1.src=kjv&c1.ref=JHN.3"))
            .containsExactly("KJV · John 3");
        assertThat(PreferencesView.describePosition("c1.src=q-ar&c1.ref=Q.2.255"))
            .containsExactly("Q-AR · Surah 2:255");
    }

    @Test
    void nothingSavedIsEmpty() {
        assertThat(PreferencesView.describePosition(null)).isEmpty();
        assertThat(PreferencesView.describePosition("")).isEmpty();
        assertThat(PreferencesView.describePosition("garbage&=&x")).isEmpty();
    }
}
