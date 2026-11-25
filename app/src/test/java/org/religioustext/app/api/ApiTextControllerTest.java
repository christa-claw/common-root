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
import org.religioustext.app.service.AudioIndexService;
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
        final ApiTextController controller = new ApiTextController(corpus, attest, mock(ApiQuotaService.class), mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
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
        final ResponseEntity<Object> chapter = controller.chapter("niv", "JHN.3.16", null, null, null);
        assertThat(chapter.getStatusCode().value()).isEqualTo(403);
        assertThat(((ApiError) chapter.getBody()).error()).isEqualTo("not_redistributable");
        verify(corpus, never()).chapter(anyString(), anyString(), anyInt());

        // scattered passages: one licensed reference poisons the whole call — nothing served
        final ResponseEntity<Object> passages = controller.passages(null, "kjv:JHN.3.16,niv:JHN.3.16", null, null);
        assertThat(passages.getStatusCode().value()).isEqualTo(403);
        verify(corpus, never()).chapter(anyString(), anyString(), anyInt());

        // the edition record itself is not text and stays readable
        final ResponseEntity<Object> record = controller.text("niv");
        assertThat(record.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<String, Object>) record.getBody())).containsEntry("downloadable", false);
    }

    @Test
    void thePhoneAppReadsLicensedEditionsAChapterAtATimeButNeverDownloadsThem() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest, mock(ApiQuotaService.class), mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
        final SourceRow niv = SourceRow.of(new String[] {
            "bible-niv", "New International Version", "NIV", "ltr", "Licensed", "", "bible", "en" });
        when(corpus.resolve("niv")).thenReturn(niv);
        when(corpus.chapter("bible-niv", "JHN", 3)).thenReturn(java.util.List.of(
            org.religioustext.app.model.VerseRef.builder().sourceId("bible-niv").bookCode("JHN")
                .bookName("John").chapterNumber(3).verseNumber(16).content("For God so loved").build()));
        final MockHttpServletRequest app = keyed("key-1");
        app.addHeader(AppReadPolicy.CLIENT_HEADER, AppReadPolicy.APP_CLIENT);

        final ResponseEntity<Object> chapter = controller.chapter("niv", "JHN.3", null, null, app);
        assertThat(chapter.getStatusCode().value()).isEqualTo(200);
        // served to the app alone: no shared cache may keep it for someone else
        assertThat(chapter.getHeaders().getCacheControl()).contains("private").doesNotContain("public");

        final ResponseEntity<Object> passages = controller.passages(null, "niv:JHN.3.16", null, app);
        assertThat(passages.getStatusCode().value()).isEqualTo(200);
        assertThat(passages.getHeaders().getCacheControl()).contains("private");

        // the whole edition stays public domain only, whoever asks
        assertThat(controller.download("niv", null, null, app).getStatusCode().value()).isEqualTo(403);
        // and without the app's header the chapter is refused as before
        assertThat(controller.chapter("niv", "JHN.3", null, null, keyed("key-1")).getStatusCode().value()).isEqualTo(403);
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
        final ApiTextController controller = new ApiTextController(corpus, attest, quota, mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
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
        final ApiTextController controller = new ApiTextController(corpus, attest, quota, mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
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
        final ApiTextController controller = new ApiTextController(corpus, attest, quota, mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
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

    // ── reading orders (the phone app's Reading order): books and chapters as the reader orders them ──

    @Test
    @SuppressWarnings("unchecked")
    void aBibleListsItsBooksInTheAskedOrderAndChronoAddsTheChapterSequence() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final ApiTextController controller = new ApiTextController(corpus,
            new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC()),
            mock(ApiQuotaService.class), mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
        when(corpus.resolve("kjv")).thenReturn(kjvRow());
        final java.util.List<ApiCorpusService.Book> chrono = java.util.List.of(
            new ApiCorpusService.Book("GEN", "Genesis", null, java.util.List.of(1, 2)),
            new ApiCorpusService.Book("JOB", "Job", null, java.util.List.of(1)));
        when(corpus.books("bible-kjv-1611", org.religioustext.app.model.DisplayOptions.OrderMode.CHRONOLOGICAL))
            .thenReturn(chrono);
        when(corpus.chronologicalChapters("bible-kjv-1611", chrono))
            .thenReturn(java.util.List.of("GEN.1", "JOB.1", "GEN.2"));
        when(corpus.books("bible-kjv-1611", org.religioustext.app.model.DisplayOptions.OrderMode.TANAKH))
            .thenReturn(chrono);

        final Map<String, Object> body = (Map<String, Object>) controller.books("kjv", null, "chrono").getBody();
        assertThat(body.get("order")).isEqualTo("chrono");
        assertThat(body.get("sequence")).isEqualTo(java.util.List.of("GEN.1", "JOB.1", "GEN.2"));
        assertThat((java.util.List<Map<String, Object>>) body.get("books"))
            .extracting(b -> b.get("code")).containsExactly("GEN", "JOB");

        // the other orders only reorder whole books, so they need no sequence
        final Map<String, Object> tanakh = (Map<String, Object>) controller.books("kjv", null, "tanakh").getBody();
        assertThat(tanakh.get("order")).isEqualTo("tanakh");
        assertThat(tanakh).doesNotContainKey("sequence");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOrderIsForBiblesAndAnUnknownOneIsCanonical() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final ApiTextController controller = new ApiTextController(corpus,
            new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC()),
            mock(ApiQuotaService.class), mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
        when(corpus.resolve("q-en")).thenReturn(SourceRow.of(new String[] {
            "quran-pickthall", "The Meaning of the Glorious Koran", "Q-EN", "ltr", "Public Domain", "", "quran", "en" }));
        when(corpus.resolve("kjv")).thenReturn(kjvRow());

        final Map<String, Object> quran = (Map<String, Object>) controller.books("q-en", null, "chrono").getBody();
        assertThat(quran.get("order")).isEqualTo("canon");
        assertThat(quran).doesNotContainKey("sequence");
        verify(corpus).books("quran-pickthall", org.religioustext.app.model.DisplayOptions.OrderMode.CANONICAL);

        final Map<String, Object> odd = (Map<String, Object>) controller.books("kjv", null, "backwards").getBody();
        assertThat(odd.get("order")).isEqualTo("canon");
    }

    // ── section titles (the reader's Titles mode) ──

    @Test
    @SuppressWarnings("unchecked")
    void aVerseNamesTheSectionItSitsUnderOnlyWhenItHasOne() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest, mock(ApiQuotaService.class), mock(AudioIndexService.class), new AppReadPolicy(java.time.Clock.systemUTC(), 300));
        when(corpus.resolve("kjv")).thenReturn(kjvRow());
        when(corpus.chapter("bible-kjv-1611", "JHN", 3)).thenReturn(java.util.List.of(
            org.religioustext.app.model.VerseRef.builder().sourceId("bible-kjv-1611").bookCode("JHN").bookName("John")
                .chapterNumber(3).verseNumber(1).chapterTitle("Jesus Teaches Nicodemus").content("There was a man").build(),
            org.religioustext.app.model.VerseRef.builder().sourceId("bible-kjv-1611").bookCode("JHN").bookName("John")
                .chapterNumber(3).verseNumber(2).content("The same came").build()));

        final Map<String, Object> body = (Map<String, Object>) controller.chapter("kjv", "JHN.3", null, null, null).getBody();
        final java.util.List<Map<String, Object>> verses = (java.util.List<Map<String, Object>>) body.get("verses");
        assertThat(verses.get(0)).containsEntry("title", "Jesus Teaches Nicodemus");
        assertThat(verses.get(1)).doesNotContainKey("title");
    }

    // ── audio (mobile app's Listen): the API points at files the audio index knows ──

    @Test
    @SuppressWarnings("unchecked")
    void chapterAndRecordSayWhereToListenOnlyWhenTheIndexHasBothFiles() {
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final AudioIndexService audio = mock(AudioIndexService.class);
        final AttestationService attest = new AttestationService("k1", "s", "", "c", "t", java.time.Clock.systemUTC());
        final ApiTextController controller = new ApiTextController(corpus, attest, mock(ApiQuotaService.class), audio, new AppReadPolicy(java.time.Clock.systemUTC(), 300));
        when(corpus.resolve("kjv")).thenReturn(kjvRow());
        final java.util.List<org.religioustext.app.model.VerseRef> john3 = java.util.List.of(
            org.religioustext.app.model.VerseRef.builder().sourceId("bible-kjv-1611").bookCode("JHN")
                .bookName("John").chapterNumber(3).verseNumber(16).content("For God so loved").build());
        when(corpus.chapter("bible-kjv-1611", "JHN", 3)).thenReturn(john3);
        when(corpus.chapter("bible-kjv-1611", "JHN", 4)).thenReturn(john3);
        when(audio.hasAudio("bible-kjv-1611")).thenReturn(true);
        when(audio.mp3Url("bible-kjv-1611", "JHN", 3)).thenReturn(java.util.Optional.of("/audio/k/v/43_JHN/003_JHN.mp3"));
        when(audio.offsetsUrl("bible-kjv-1611", "JHN", 3)).thenReturn(java.util.Optional.of("/audio/k/v/43_JHN/003_JHN.json"));
        when(audio.mp3Url("bible-kjv-1611", "JHN", 4)).thenReturn(java.util.Optional.empty());
        when(audio.offsetsUrl("bible-kjv-1611", "JHN", 4)).thenReturn(java.util.Optional.empty());

        final Map<String, Object> narrated = (Map<String, Object>) controller.chapter("kjv", "JHN.3.16", null, null, null).getBody();
        assertThat((Map<String, Object>) narrated.get("audio"))
            .containsEntry("mp3", "/audio/k/v/43_JHN/003_JHN.mp3")
            .containsEntry("offsets", "/audio/k/v/43_JHN/003_JHN.json");

        final Map<String, Object> silent = (Map<String, Object>) controller.chapter("kjv", "JHN.4", null, null, null).getBody();
        assertThat(silent).doesNotContainKey("audio");

        assertThat((Map<String, Object>) controller.text("kjv").getBody()).containsEntry("audio", true);
    }
}
