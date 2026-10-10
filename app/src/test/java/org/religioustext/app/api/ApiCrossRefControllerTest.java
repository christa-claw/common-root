// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.CrossRefQueryService;
import org.religioustext.app.service.CrossRefQueryService.XRef;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The route's contract, against a hand-rolled query service (no database, no mocks). */
class ApiCrossRefControllerTest {

    private static ApiCrossRefController controller(final Map<Integer, List<XRef>> aChapter) {
        return new ApiCrossRefController(new CrossRefQueryService(null) {
            @Override public Map<Integer, List<XRef>> forChapter(final String b, final int c) { return aChapter; }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void servesLinkFormatRefsWithIdsVotesAndAttribution() {
        final var res = controller(Map.of(16, List.of(
            new XRef("xrf-1", "ROM", 5, 8, null, null, 984),
            new XRef("xrf-2", "1JN", 4, 9, 4, 10, 698)))).chapter("jhn", 3);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("public").contains("max-age=3600");
        final Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("book", "JHN").containsEntry("chapter", 3)
            .containsEntry("numbering", "KJV");
        final var verse16 = ((Map<String, List<Map<String, Object>>>) body.get("verses")).get("16");
        assertThat(verse16).extracting(m -> m.get("ref")).containsExactly("ROM.5.8", "1JN.4.9-10");
        assertThat(verse16.get(0)).containsEntry("id", "xrf-1").containsEntry("votes", 984);
        assertThat((Map<String, String>) body.get("attribution"))
            .containsEntry("licence", "CC BY 4.0").containsKey("url");
    }

    @Test
    void chapterWithoutReferencesIsAnEmptyOk() {
        final ResponseEntity<Object> res = controller(Map.of()).chapter("GEN", 1);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) ((Map<?, ?>) res.getBody()).get("verses"))).isEmpty();
    }

    @Test
    void unknownBookIs404AndBadChapterIs400() {
        assertThat(controller(Map.of()).chapter("QUR", 1).getStatusCode().value()).isEqualTo(404);
        assertThat(controller(Map.of()).chapter("GEN", 0).getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiError) controller(Map.of()).chapter("QUR", 1).getBody()).error())
            .isEqualTo("no_such_book");
    }
}
