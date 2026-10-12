// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.LexiconService.Entry;

import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Against small test bundles (src/test/resources/testlexicon): English base plus a partial Finnish file. */
class LexiconServiceTest {

    private final LexiconService svc = new LexiconService("testlexicon.hebrew");

    @Test
    void englishIsTheBaseAndNotMachineTranslated() {
        final Entry e = svc.hebrew("H1", Locale.ENGLISH).orElseThrow();
        assertThat(e.gloss()).isEqualTo("father");
        assertThat(e.definition()).isEqualTo("father, in a literal sense");
        assertThat(e.lemma()).isEqualTo("אָב");
        assertThat(e.xlit()).isEqualTo("ʾāb");
        assertThat(e.machineTranslated()).isFalse();
    }

    @Test
    void aTranslatedLocaleGetsItsOwnGlossAndDefinitionButTheNeutralLemma() {
        final Entry e = svc.hebrew("H1", Locale.forLanguageTag("fi")).orElseThrow();
        assertThat(e.gloss()).isEqualTo("isä");
        assertThat(e.definition()).isEqualTo("isä kirjaimellisessa merkityksessä");
        assertThat(e.lemma()).isEqualTo("אָב");              // never translated, taken from the English base
        assertThat(e.machineTranslated()).isTrue();
    }

    @Test
    void anUntranslatedKeyFallsBackToEnglishPerKey() {
        final Entry e = svc.hebrew("H2", Locale.forLanguageTag("fi")).orElseThrow();
        assertThat(e.gloss()).isEqualTo("same");
        assertThat(e.definition()).isEqualTo("same definition");
        assertThat(e.machineTranslated()).isFalse();           // nothing here came from the locale file
    }

    @Test
    void aLocaleWithNoFileGetsEnglish() {
        final Entry e = svc.hebrew("H1", Locale.forLanguageTag("tr")).orElseThrow();
        assertThat(e.gloss()).isEqualTo("father");
        assertThat(e.machineTranslated()).isFalse();
    }

    @Test
    void nullLocaleIsEnglish() {
        assertThat(svc.hebrew("H1", null).orElseThrow().gloss()).isEqualTo("father");
    }

    @Test
    void theJvmDefaultLocaleDoesNotLeakIntoEnglishRequests() {
        final Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("fi"));
            assertThat(svc.hebrew("H1", Locale.ENGLISH).orElseThrow().gloss()).isEqualTo("father");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void anEntryWithoutADefinitionHasAnEmptyOne() {
        assertThat(svc.hebrew("H3", Locale.ENGLISH).orElseThrow().definition()).isEmpty();
    }

    @Test
    void unknownAndMalformedIdsAreEmpty() {
        assertThat(svc.hebrew("H9999", Locale.ENGLISH)).isEqualTo(Optional.empty());
        for (final String bad : new String[] {null, "", "430", "h1", "H", "H1; DROP", "../x", "H123456"})
            assertThat(svc.hebrew(bad, Locale.ENGLISH)).as(String.valueOf(bad)).isEmpty();
    }

    @Test
    void theShippedEnglishFileCoversTheNumbersTheReaderShows() {
        final LexiconService real = new LexiconService();
        for (final String id : new String[] {"H430", "H1254", "H7225", "H8064", "H776", "H853"}) {
            final Entry e = real.hebrew(id, Locale.ENGLISH).orElseThrow();
            assertThat(e.gloss()).as(id).isNotBlank();
            assertThat(e.lemma()).as(id).isNotBlank();
        }
        assertThat(real.hebrew("H1254", Locale.ENGLISH).orElseThrow().gloss()).startsWith("create");
    }

    @Test
    void theShippedFinnishFileTranslatesTheFrequentWordsAndFallsBackForTheRest() {
        final LexiconService real = new LexiconService();
        final Locale fi = Locale.forLanguageTag("fi");
        final Entry elohim = real.hebrew("H430", fi).orElseThrow();
        assertThat(elohim.gloss()).isEqualTo("jumalat; Jumala; tuomarit");
        assertThat(elohim.machineTranslated()).isTrue();
        assertThat(elohim.lemma()).isEqualTo(real.hebrew("H430", Locale.ENGLISH).orElseThrow().lemma());
        // a word beyond the translated batch: English, and not labelled machine-translated
        final Entry rare = real.hebrew("H8674", fi).orElseThrow();
        assertThat(rare.gloss()).isEqualTo(real.hebrew("H8674", Locale.ENGLISH).orElseThrow().gloss());
        assertThat(rare.machineTranslated()).isFalse();
    }
}
