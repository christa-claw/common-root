// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.religioustext.app.model.DisplayOptions.OrderMode;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The reading orders as the API serves them: the reader's own queries, with
 * the chronological chapter list turned from book names into book codes.
 */
class ApiCorpusServiceOrderTest {

    private final TextQueryService texts = mock(TextQueryService.class);
    private final ApiCorpusService corpus = new ApiCorpusService(
        texts, mock(BaseXProperties.class), mock(RestTemplate.class), Clock.systemUTC());

    @Test
    void booksComeInTheAskedOrder() {
        when(texts.listBooksWithChapterCounts("bible-kjv", OrderMode.TANAKH)).thenReturn(List.of(
            new String[] {"Genesis", "2", "", "GEN", "1,2"},
            new String[] {"Joshua", "1", "", "JOS", "1"}));
        assertThat(corpus.books("bible-kjv", OrderMode.TANAKH))
            .extracting(ApiCorpusService.Book::code).containsExactly("GEN", "JOS");
    }

    @Test
    void chronologicalChaptersAreCodedAndRevisitABook() {
        when(texts.listChronologicalChunks("bible-kjv")).thenReturn(List.of(
            new String[] {"Genesis", "1"},
            new String[] {"Job", "1"},
            new String[] {"Genesis", "12"},
            new String[] {"Tobit", "1"},       // a book this table of contents does not know
            new String[] {"Job"}));            // a malformed row
        final List<ApiCorpusService.Book> books = List.of(
            new ApiCorpusService.Book("GEN", "Genesis", null, List.of(1, 12)),
            new ApiCorpusService.Book("JOB", "Job", null, List.of(1)));
        assertThat(corpus.chronologicalChapters("bible-kjv", books))
            .containsExactly("GEN.1", "JOB.1", "GEN.12");
    }
}
