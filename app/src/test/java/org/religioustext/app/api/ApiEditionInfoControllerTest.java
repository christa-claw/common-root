// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.ui.views.EditionInfo;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An edition's description as the phone app gets it: the site's own info page,
 * in the asked-for language or English, with the site-only link markup gone.
 */
class ApiEditionInfoControllerTest {

    @Test
    void aTranslatedPageIsServedInItsLanguage() {
        final EditionInfo fi = ApiEditionInfoController.pageFor("abd", "fi");
        assertThat(fi).isNotNull();
        assertThat(fi.lang).isEqualTo("fi");
        assertThat(fi.abbr).isEqualTo("ABD");
    }

    @Test
    void anUnknownLanguageOrNoneFallsBackToEnglish() {
        assertThat(ApiEditionInfoController.pageFor("ABD", "xx").lang).isEqualTo("en");
        assertThat(ApiEditionInfoController.pageFor("ABD", null).lang).isEqualTo("en");
        // a region or script subtag reads the language's page
        assertThat(ApiEditionInfoController.pageFor("ABD", "zh-Hant").lang).isEqualTo("zh");
    }

    @Test
    void anEditionWithoutAPageHasNone() {
        assertThat(ApiEditionInfoController.pageFor("NOSUCH", "en")).isNull();
        assertThat(ApiEditionInfoController.pageFor(null, "en")).isNull();
        assertThat(ApiEditionInfoController.hasPage("q-ar")).isTrue();
        assertThat(ApiEditionInfoController.hasPage("NOSUCH")).isFalse();
    }

    @Test
    void bothHalvesOfAHadithPairShareTheCollectionsPage() {
        assertThat(ApiEditionInfoController.pageKey("ABD-AR")).isEqualTo("ABD");
        assertThat(ApiEditionInfoController.pageKey("abd-en")).isEqualTo("ABD");
        // a Qur'an's language suffix is part of its name: each has its own page
        assertThat(ApiEditionInfoController.pageKey("Q-EN")).isEqualTo("Q-EN");
        // and a suffix on an edition whose page is not a pair finds nothing
        assertThat(ApiEditionInfoController.pageKey("KJV-EN")).isNull();
        assertThat(ApiEditionInfoController.pageKey("ABD-FR")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void siteLinksBecomePlainNamesAndParagraphsSplit() {
        final Map<String, Object> body = ApiEditionInfoController.view("abd",
            ApiEditionInfoController.pageFor("ABD", "en"));
        final List<String> paragraphs = (List<String>) body.get("paragraphs");
        assertThat(paragraphs).hasSize(4);
        final List<Map<String, String>> facts = (List<Map<String, String>>) body.get("facts");
        assertThat(facts).isNotEmpty();
        assertThat(facts).allSatisfy(f -> assertThat(f.get("value")).doesNotContain("[["));
        assertThat(facts.get(2).get("value")).contains("Sahih al-Bukhari");
        assertThat(body.get("page")).isEqualTo("/edition/ABD");
        // the narrative links other editions too, and loses the markup the same way
        final List<String> kjv = (List<String>) ApiEditionInfoController.view("kjv",
            ApiEditionInfoController.pageFor("KJV", "en")).get("paragraphs");
        assertThat(String.join(" ", kjv)).contains("Geneva Bible").doesNotContain("[[");
    }
}
