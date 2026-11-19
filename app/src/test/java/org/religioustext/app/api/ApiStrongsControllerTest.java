// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.LexiconService;
import org.religioustext.app.service.WordLayerService;
import org.religioustext.app.service.WordLayerService.Occurrence;
import org.religioustext.app.service.WordLayerService.Word;
import org.religioustext.app.ui.views.reader.SourceRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Strong's routes: words of a passage, a lexicon entry, where a number occurs. */
class ApiStrongsControllerTest {

    private static ApiStrongsController controller(final String aLicense, final boolean theLayerFlag) {
        final WordLayerService words = new WordLayerService(null, null, "words") {
            @Override public boolean hasLayer(final String id) { return theLayerFlag; }
            @Override public Map<Integer, List<Word>> forChapter(final String id, final String b, final int c) {
                final Map<Integer, List<Word>> m = new LinkedHashMap<>();
                m.put(1, List.of(new Word("01xeN", "בְּרֵאשִׁית", "H7225", null, " "),
                                 new Word("01Nvk", "בָּרָא", "H1254", "a", " ")));
                m.put(2, List.of(new Word("02abc", "וְהָאָרֶץ", null, null, "")));
                return m;
            }
            @Override public int countOccurrences(final String id, final String s) { return 3; }
            @Override public List<Occurrence> occurrences(final String id, final String s, final int limit) {
                return List.of(new Occurrence("GEN", 1, 1, "בָּרָא"), new Occurrence("GEN", 1, 21, "וַיִּבְרָא"))
                    .subList(0, Math.min(limit, 2));
            }
        };
        final LexiconService lexicon = new LexiconService() {
            @Override public Optional<Entry> entry(final String s, final Locale l) {
                return "H1254".equals(s)
                    ? Optional.of(new Entry(s, "בָּרָא", "bârâʼ", "luoda", "luoda, tehdä", "fi".equals(l.getLanguage())))
                    : Optional.empty();
            }
        };
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final SourceRow row = mock(SourceRow.class);
        when(row.id()).thenReturn("bible-he-wlc");
        when(row.abbreviation()).thenReturn("WLC");
        when(row.license()).thenReturn(aLicense);
        when(row.direction()).thenReturn("rtl");
        when(corpus.resolve("wlc")).thenReturn(row);
        return new ApiStrongsController(corpus, words, lexicon, new AppReadPolicy(java.time.Clock.systemUTC(), 300));
    }

    @Test
    @SuppressWarnings("unchecked")
    void wordsOfAVerseCarryNumbersAndTheCredit() {
        final var res = controller("Public domain", true).words("wlc", "GEN.1.1", null, null);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        final Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("text", "wlc").containsEntry("direction", "rtl").containsEntry("ref", "GEN.1.1");
        final List<Map<String, Object>> verses = (List<Map<String, Object>>) body.get("verses");
        assertThat(verses).hasSize(1);                               // verse 2 is outside the span
        final List<Map<String, Object>> ws = (List<Map<String, Object>>) verses.get(0).get("words");
        assertThat(ws.get(0)).containsEntry("strongs", "H7225").doesNotContainKey("sense")
            .doesNotContainKey("gloss");                             // the lexicon has no such entry
        assertThat(ws.get(1)).containsEntry("strongs", "H1254").containsEntry("sense", "a")
            .containsEntry("gloss", "luoda");
        assertThat((Map<String, String>) body.get("attribution"))
            .containsEntry("source", "Open Scriptures Hebrew Bible Project").containsEntry("licence", "CC BY 4.0");
    }

    @Test
    void wholeChapterKeepsUnnumberedWordsUnnumbered() {
        final var body = (Map<?, ?>) controller("Public domain", true).words("wlc", "GEN.1", null, null).getBody();
        final var verses = (List<?>) body.get("verses");
        assertThat(verses).hasSize(2);
        final var word = (Map<?, ?>) ((List<?>) ((Map<?, ?>) verses.get(1)).get("words")).get(0);
        assertThat(word.containsKey("strongs")).isFalse();
    }

    @Test
    void wordRoutesAreGatedLikeText() {
        assertThat(((ApiError) controller("Public domain", true).words("nope", "GEN.1", null, null).getBody()).error())
            .isEqualTo("unknown_source");
        assertThat(((ApiError) controller("Copyright", true).words("wlc", "GEN.1", null, null).getBody()).error())
            .isEqualTo("not_redistributable");
        assertThat(((ApiError) controller("Public domain", false).words("wlc", "GEN.1", null, null).getBody()).error())
            .isEqualTo("no_word_layer");
        assertThat(((ApiError) controller("Public domain", true).words("wlc", "garbage", null, null).getBody()).error())
            .isEqualTo("bad_ref");
        assertThat(((ApiError) controller("Copyright", true).occurrences("H1254", "wlc", null, null).getBody()).error())
            .isEqualTo("not_redistributable");
    }

    @Test
    @SuppressWarnings("unchecked")
    void entryIsLocalisedAndSaysWhenMachineTranslated() {
        final var c = controller("Public domain", true);
        final Map<String, Object> fi = (Map<String, Object>) c.entry("h1254", "fi").getBody();
        assertThat(fi).containsEntry("strongs", "H1254").containsEntry("language", "fi")
            .containsEntry("machineTranslated", true).containsEntry("lemmaDirection", "rtl");
        final Map<String, Object> en = (Map<String, Object>) c.entry("H1254", null).getBody();
        assertThat(en).containsEntry("language", "en").containsEntry("machineTranslated", false);
        assertThat(((ApiError) c.entry("H9", null).getBody()).error()).isEqualTo("no_such_entry");
        assertThat(((ApiError) c.entry("X12", null).getBody()).error()).isEqualTo("bad_strongs");
    }

    @Test
    @SuppressWarnings("unchecked")
    void occurrencesReportTheTotalAndTruncation() {
        final var c = controller("Public domain", true);
        final Map<String, Object> body = (Map<String, Object>) c.occurrences("h1254", "wlc", null, null).getBody();
        assertThat(body).containsEntry("total", 3).containsEntry("truncated", true).containsEntry("text", "wlc");
        assertThat((List<Map<String, Object>>) body.get("occurrences"))
            .extracting(m -> m.get("ref")).containsExactly("GEN.1.1", "GEN.1.21");
        assertThat(((ApiError) c.occurrences("H1254", "wlc", 0, null).getBody()).error()).isEqualTo("bad_limit");
        assertThat(((ApiError) c.occurrences("H1254", "wlc", 5000, null).getBody()).error()).isEqualTo("bad_limit");
    }
}
