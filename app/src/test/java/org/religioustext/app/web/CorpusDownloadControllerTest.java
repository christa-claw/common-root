// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** The retired anonymous download (issue #2): gone, with a pointer, never a redirect. */
class CorpusDownloadControllerTest {

    @Test
    void perEditionDownloadIsGoneWithAPointerToTheApi() {
        final CorpusDownloadController c = new CorpusDownloadController(null, null);
        final ResponseEntity<String> r = c.download("KJV");
        assertThat(r.getStatusCode().value()).isEqualTo(410);
        assertThat(r.getBody()).contains("/api/v1/texts/kjv/download").contains("/api/docs");
        assertThat(r.getHeaders().getLocation()).isNull();            // no silent reopening
    }

    @Test
    void unsafeKeysAreNotEchoed() {
        final CorpusDownloadController c = new CorpusDownloadController(null, null);
        assertThat(c.download("../etc/passwd").getBody()).doesNotContain("etc").contains("<token>");
    }

    @Test
    void licenceGateStillReadsTheCorpusWording() {
        assertThat(CorpusDownloadController.isPublicDomain("Public Domain")).isTrue();
        assertThat(CorpusDownloadController.isPublicDomain("Licensed")).isFalse();
    }
}
