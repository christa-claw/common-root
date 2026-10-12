// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.religioustext.app.i18n.TranslationProvider;
import org.religioustext.app.service.LexiconService;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LexiconControllerTest {

    private final LexiconController c = new LexiconController(
        new LexiconService("testlexicon.hebrew"), new TranslationProvider());

    @Test
    void servesAnEntryWithCreditAndADayOfPublicCaching() {
        final ResponseEntity<Map<String, Object>> r = c.hebrew("H1", "en");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getCacheControl()).contains("public").contains("max-age=86400");
        final Map<String, Object> b = r.getBody();
        assertThat(b).containsEntry("strongs", "H1").containsEntry("gloss", "father")
            .containsEntry("dir", "ltr").containsEntry("note", "");
        assertThat(b.get("credit").toString()).contains("CC BY 4.0").contains("openscriptures");
    }

    @Test
    void aTranslatedLocaleCarriesItsTextAndTheMachineTranslatedLabel() {
        final Map<String, Object> b = c.hebrew("H1", "fi").getBody();
        assertThat(b).containsEntry("gloss", "isä").containsEntry("note", "Konekäännös")
            .containsEntry("hint", "Kaksoisnapsauta lisätietoja varten");
    }

    @Test
    void rightToLeftLocalesAreMarkedEvenWhenFallingBackToEnglish() {
        final Map<String, Object> b = c.hebrew("H1", "ar").getBody();
        assertThat(b).containsEntry("dir", "rtl").containsEntry("gloss", "father").containsEntry("note", "");
        assertThat(c.hebrew("H1", "he").getBody()).containsEntry("dir", "rtl");
    }

    @Test
    void lowerCaseIdsAreAcceptedAndUnknownOnesAre404() {
        assertThat(c.hebrew("h1", "en").getStatusCode().value()).isEqualTo(200);
        assertThat(c.hebrew("H9999", "en").getStatusCode().value()).isEqualTo(404);
        assertThat(c.hebrew("not-an-id", "en").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void anUnknownLanguageIsEnglish() {
        assertThat(c.hebrew("H1", "xx").getBody()).containsEntry("gloss", "father");
    }
}
