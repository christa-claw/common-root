// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.api.ApiTextController.Span;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.ApiQuotaService;
import org.religioustext.app.model.user.ApiKey;
import org.springframework.mock.web.MockHttpServletRequest;
import org.religioustext.app.service.AttestationService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
        final ApiTextController controller = new ApiTextController(corpus, attest, mock(ApiQuotaService.class));
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

    // ── /texts/{token}/download (issue #1): whole edition behind the key ──

    private static SourceRow kjvRow() {
        return SourceRow.of(new String[] {
            "bible-kjv-1611", "King James Version", "KJV", "ltr", "Public Domain", "", "bible", "en" });
    }

    private static MockHttpServletRequest keyed(final String aRowId) {
        final ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(aRowId);
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ApiAuthFilter.ATTR_KEY, new ResolvedKey(key, null));
        return req;
    }

    @Test
    void downloadServesTheAuthenticDocumentAndMetersBytes() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final ApiQuotaService quota = mock(ApiQuotaService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "corpus-7", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest, quota);
        when(corpus.resolve("kjv")).thenReturn(kjvRow());
        when(corpus.document("bible-kjv-1611")).thenReturn("<text id=\"bible-kjv-1611\"/>");
        when(quota.wouldExceedBytes(anyString(), anyLong()))
            .thenReturn(new ApiQuotaService.Decision(true, 100, 50, 0, 0));

        final ResponseEntity<Object> r = controller.download("kjv", null, null, keyed("key-1"));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getETag()).isEqualTo("\"bible-kjv-1611@corpus-7\"");
        assertThat(r.getHeaders().getFirst("Content-Disposition")).contains("kjv.xml");
        assertThat(r.getHeaders().getFirst("X-CommonRoot-Corpus")).isEqualTo("corpus-7");
        assertThat(new String((byte[]) r.getBody(), java.nio.charset.StandardCharsets.UTF_8)).startsWith("<text");
        verify(quota).countBytes("key-1", "<text id=\"bible-kjv-1611\"/>".getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    void downloadRepeatPullIs304AndCountsNothing() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final ApiQuotaService quota = mock(ApiQuotaService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "corpus-7", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest, quota);
        when(corpus.resolve("kjv")).thenReturn(kjvRow());

        final ResponseEntity<Object> r = controller.download("kjv", null, "\"bible-kjv-1611@corpus-7\"", keyed("key-1"));

        assertThat(r.getStatusCode().value()).isEqualTo(304);
        verify(corpus, never()).document(anyString());
        verify(quota, never()).countBytes(anyString(), anyLong());
    }

    @Test
    void downloadRefusesLicensedEditionsJsonAndExhaustedAllowance() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final ApiQuotaService quota = mock(ApiQuotaService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest, quota);
        when(corpus.resolve("kjv")).thenReturn(kjvRow());
        when(corpus.resolve("niv")).thenReturn(SourceRow.of(new String[] {
            "bible-niv", "New International Version", "NIV", "ltr", "Licensed", "", "bible", "en" }));
        when(corpus.document("bible-kjv-1611")).thenReturn("<text/>");

        final ResponseEntity<Object> niv = controller.download("niv", null, null, keyed("key-1"));
        assertThat(niv.getStatusCode().value()).isEqualTo(403);
        assertThat(((ApiError) niv.getBody()).error()).isEqualTo("not_redistributable");
        verify(corpus, never()).document(anyString());

        final ResponseEntity<Object> json = controller.download("kjv", "json", null, keyed("key-1"));
        assertThat(json.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiError) json.getBody()).error()).isEqualTo("format_not_available");

        when(quota.wouldExceedBytes(anyString(), anyLong()))
            .thenReturn(new ApiQuotaService.Decision(false, 100, 0, 1_900_000_000L, 3600));
        final ResponseEntity<Object> full = controller.download("kjv", null, null, keyed("key-1"));
        assertThat(full.getStatusCode().value()).isEqualTo(429);
        assertThat(((ApiError) full.getBody()).error()).isEqualTo("bytes_quota_exceeded");
        assertThat(full.getHeaders().getFirst("Retry-After")).isEqualTo("3600");
        verify(quota, never()).countBytes(anyString(), anyLong());
    }
}
