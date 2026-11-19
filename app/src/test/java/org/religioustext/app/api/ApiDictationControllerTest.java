// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.api.ApiDictationController.TidyIn;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.model.user.User;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.dictation.DictationAllowance;
import org.religioustext.app.service.dictation.DictationException;
import org.religioustext.app.service.dictation.SpeechTranscriber;
import org.religioustext.app.service.dictation.TranscriptTidier;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The dictation routes: tidy a phone's transcript, or transcribe a recording as the fallback. */
class ApiDictationControllerTest {

    /** A tidier that upper-cases, so a tidied text is recognisable. */
    private static TranscriptTidier tidier(final boolean theAvailableFlag, final boolean theFailFlag) {
        return new TranscriptTidier() {
            @Override public boolean available() { return theAvailableFlag; }
            @Override public Tidied tidy(final String t, final String l) {
                if (theFailFlag) throw new DictationException("down", null);
                return new Tidied(t.toUpperCase(), true);
            }
        };
    }

    private static SpeechTranscriber transcriber(final boolean theAvailableFlag, final String aTranscript) {
        return new SpeechTranscriber() {
            @Override public boolean available() { return theAvailableFlag; }
            @Override public String transcribe(final byte[] a, final String f, final String c, final String l) {
                return aTranscript;
            }
        };
    }

    private static ApiDictationController controller(final TranscriptTidier aTidier,
                                                     final SpeechTranscriber aTranscriber, final int aPerDay) {
        return new ApiDictationController(aTidier, aTranscriber, new DictationAllowance(aPerDay));
    }

    private static MockHttpServletRequest keyed() {
        final User owner = mock(User.class);
        when(owner.getId()).thenReturn("usr-1");
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ApiAuthFilter.ATTR_KEY, new ResolvedKey(mock(ApiKey.class), owner));
        return req;
    }

    private static MockMultipartFile audio() {
        return new MockMultipartFile("audio", "note.m4a", "audio/mp4", new byte[] { 1, 2, 3 });
    }

    private static String slug(final ResponseEntity<Object> aResponse) {
        return ((ApiError) aResponse.getBody()).error();
    }

    @Test
    void tidyReturnsTheCleanedTextAndStoresNothing() {
        final var res = controller(tidier(true, false), transcriber(false, ""), 5)
            .tidy(new TidyIn("  um so john three sixteen ", "en"), keyed());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        assertThat((Map<?, ?>) res.getBody()).isEqualTo(Map.of("text", "UM SO JOHN THREE SIXTEEN", "tidied", true));
    }

    @Test
    void tidyRefusesBadInputUnconfiguredServersAndFailures() {
        final var on = controller(tidier(true, false), transcriber(false, ""), 5);
        assertThat(slug(on.tidy(new TidyIn(" ", null), keyed()))).isEqualTo("empty_body");
        assertThat(slug(on.tidy(new TidyIn("x".repeat(10_001), null), keyed()))).isEqualTo("body_too_long");
        assertThat(slug(on.tidy(new TidyIn("x", null), new MockHttpServletRequest()))).isEqualTo("missing_key");
        assertThat(controller(tidier(false, false), transcriber(false, ""), 5)
            .tidy(new TidyIn("x", null), keyed()).getStatusCode().value()).isEqualTo(503);
        assertThat(slug(controller(tidier(true, true), transcriber(false, ""), 5)
            .tidy(new TidyIn("x", null), keyed()))).isEqualTo("upstream_failed");
    }

    @Test
    void theDailyAllowanceIsPerAccountAcrossBothRoutes() {
        final var c = controller(tidier(true, false), transcriber(true, "heard"), 2);
        assertThat(c.tidy(new TidyIn("a", null), keyed()).getStatusCode().value()).isEqualTo(200);
        assertThat(c.transcribe(audio(), null, true, keyed()).getStatusCode().value()).isEqualTo(200);
        final var third = c.tidy(new TidyIn("c", null), keyed());
        assertThat(third.getStatusCode().value()).isEqualTo(429);
        assertThat(slug(third)).isEqualTo("dictation_limit");
        // Refused input never spends the allowance.
        final var fresh = controller(tidier(true, false), transcriber(true, "heard"), 1);
        fresh.tidy(new TidyIn(" ", null), keyed());
        assertThat(fresh.tidy(new TidyIn("a", null), keyed()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void transcribeReturnsWhatWasHeardAndItsTidiedForm() {
        final var res = controller(tidier(true, false), transcriber(true, "uh grace alone"), 5)
            .transcribe(audio(), "en", true, keyed());
        assertThat((Map<?, ?>) res.getBody()).isEqualTo(
            Map.of("transcript", "uh grace alone", "text", "UH GRACE ALONE", "tidied", true));
    }

    @Test
    void transcribeKeepsTheTranscriptWhenTidyingIsOffUnavailableOrFails() {
        final Map<String, Object> asHeard = Map.of("transcript", "heard", "text", "heard", "tidied", false);
        assertThat((Map<?, ?>) controller(tidier(true, false), transcriber(true, "heard"), 5)
            .transcribe(audio(), null, false, keyed()).getBody()).isEqualTo(asHeard);
        assertThat((Map<?, ?>) controller(tidier(false, false), transcriber(true, "heard"), 5)
            .transcribe(audio(), null, true, keyed()).getBody()).isEqualTo(asHeard);
        assertThat((Map<?, ?>) controller(tidier(true, true), transcriber(true, "heard"), 5)
            .transcribe(audio(), null, true, keyed()).getBody()).isEqualTo(asHeard);
        // Silence: nothing to tidy.
        assertThat((Map<?, ?>) controller(tidier(true, false), transcriber(true, ""), 5)
            .transcribe(audio(), null, true, keyed()).getBody())
            .isEqualTo(Map.of("transcript", "", "text", "", "tidied", false));
    }

    @Test
    void transcribeRefusesWhatIsNotAUsableRecording() {
        final var c = controller(tidier(true, false), transcriber(true, "heard"), 5);
        assertThat(slug(c.transcribe(new MockMultipartFile("audio", "n.m4a", "audio/mp4", new byte[0]),
            null, true, keyed()))).isEqualTo("bad_audio");
        assertThat(slug(c.transcribe(new MockMultipartFile("audio", "n.pdf", "application/pdf", new byte[] { 1 }),
            null, true, keyed()))).isEqualTo("bad_audio");
        assertThat(slug(c.transcribe(new MockMultipartFile("audio", "n.m4a", "audio/mp4",
            new byte[(int) ApiDictationController.MAX_AUDIO_BYTES + 1]), null, true, keyed())))
            .isEqualTo("audio_too_large");
        assertThat(slug(controller(tidier(true, false), transcriber(false, ""), 5)
            .transcribe(audio(), null, true, keyed()))).isEqualTo("transcription_unavailable");
        assertThat(ApiDictationController.isAudio("video/mp4")).isTrue();      // how some phones label m4a
        assertThat(ApiDictationController.isAudio("text/plain")).isFalse();
    }
}
