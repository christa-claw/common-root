package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.api.ApiTextController.Span;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.AttestationService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure parts of the scripture routes: format negotiation and how a
 * link-format reference becomes a corpus address (docs/api-test-cases.md P8–P11
 * adjacent; the BaseX-backed cases are integration tests).
 */
class ApiTextControllerTest {

    @Test
    void queryParameterWinsOverAccept() {
        assertThat(ApiTextController.negotiate("xml", "application/json")).isEqualTo("xml");
        assertThat(ApiTextController.negotiate("json", "application/xml")).isEqualTo("json");
        assertThat(ApiTextController.negotiate("text", null)).isEqualTo("text");
        assertThat(ApiTextController.negotiate("csv", null)).isNull();          // unknown → 400 upstream
    }

    @Test
    void acceptIsTheFallbackAndJsonTheDefault() {
        assertThat(ApiTextController.negotiate(null, "application/xml")).isEqualTo("xml");
        assertThat(ApiTextController.negotiate(null, "text/xml;q=0.9")).isEqualTo("xml");
        assertThat(ApiTextController.negotiate(null, "text/plain")).isEqualTo("text");
        assertThat(ApiTextController.negotiate(null, "*/*")).isEqualTo("json");
        assertThat(ApiTextController.negotiate(null, null)).isEqualTo("json");
        assertThat(ApiTextController.negotiate("", null)).isEqualTo("json");
    }

    @Test
    void bibleChapterAndVerseSpans() {
        final Span chapter = Span.of("JHN.1");
        assertThat(chapter.bookCode()).isEqualTo("JHN");
        assertThat(chapter.chapter()).isEqualTo(1);
        assertThat(chapter.from()).isZero();                 // whole chapter

        final Span verse = Span.of("jhn.3.16");             // case-insensitive token
        assertThat(verse.bookCode()).isEqualTo("JHN");
        assertThat(verse.from()).isEqualTo(16);
        assertThat(verse.to()).isEqualTo(16);

        final Span range = Span.of("PHP.4.10-13");
        assertThat(range.from()).isEqualTo(10);
        assertThat(range.to()).isEqualTo(13);
        assertThat(range.crossesChapters()).isFalse();
        assertThat(range.format()).isEqualTo("PHP.4.10-13");
    }

    @Test
    void quranAddressingHasNoMiddleLevel() {
        final Span ayah = Span.of("Q.2.255");
        assertThat(ayah.bookCode()).isEqualTo("2");          // surah as book code
        assertThat(ayah.chapter()).isEqualTo(1);             // fixed
        assertThat(ayah.from()).isEqualTo(255);
        assertThat(ayah.crossesChapters()).isFalse();
    }

    @Test
    void crossChapterRangesAreRefusedNotGuessed() {
        assertThat(Span.of("ISA.52.13-53.12").crossesChapters()).isTrue();
    }

    @Test
    void malformedReferencesAreNull() {
        assertThat(Span.of("NOPE")).isNull();
        assertThat(Span.of("JHN.x")).isNull();
        assertThat(Span.of("")).isNull();
        assertThat(Span.of(null)).isNull();
    }

    // The licence gate (docs/api-test-cases.md K12, adapted to the 2026-09-06
    // decision): a licensed edition is refused by the text routes with the
    // same 403 the download uses, whatever key was presented, and BEFORE any
    // corpus read. Metadata stays open.
    @Test
    @SuppressWarnings("unchecked")
    void licensedEditionsAreNotServedByTheTextRoutes() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest);
        // listSources() row layout: id | translation | abbreviation | direction | license | source | type | lang
        final SourceRow niv = SourceRow.of(new String[] {
            "bible-niv", "New International Version", "NIV", "ltr", "Licensed", "", "bible", "en" });
        final SourceRow kjv = SourceRow.of(new String[] {
            "bible-kjv-1611", "King James Version", "KJV", "ltr", "Public Domain", "", "bible", "en" });
        when(corpus.resolve("niv")).thenReturn(niv);
        when(corpus.resolve("kjv")).thenReturn(kjv);

        assertThat(ApiTextController.redistributable(niv)).isFalse();
        assertThat(ApiTextController.redistributable(kjv)).isTrue();

        // chapter route
        final ResponseEntity<Object> chapter = controller.chapter("niv", "JHN.3.16", null, null);
        assertThat(chapter.getStatusCode().value()).isEqualTo(403);
        assertThat(((ApiError) chapter.getBody()).error()).isEqualTo("not_redistributable");
        verify(corpus, never()).chapter(anyString(), anyString(), anyInt());

        // scattered passages: one licensed reference poisons the whole call — nothing served
        final ResponseEntity<Object> passages = controller.passages(null, "kjv:JHN.3.16,niv:JHN.3.16", null);
        assertThat(passages.getStatusCode().value()).isEqualTo(403);
        verify(corpus, never()).chapter(anyString(), anyString(), anyInt());

        // the edition record itself is not text and stays readable
        final ResponseEntity<Object> record = controller.text("niv");
        assertThat(record.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<String, Object>) record.getBody())).containsEntry("downloadable", false);
    }
}
