// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import jakarta.servlet.http.HttpServletRequest;
import org.religioustext.app.model.user.User;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.dictation.DictationAllowance;
import org.religioustext.app.service.dictation.DictationException;
import org.religioustext.app.service.dictation.SpeechTranscriber;
import org.religioustext.app.service.dictation.TranscriptTidier;
import org.religioustext.app.service.dictation.TranscriptTidier.Tidied;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Spoken notes (docs/api-spec.md §0): turn what a user dictated into text they can save
 * through the write routes.
 * <ul>
 *   <li>{@code POST /api/v1/dictation/tidy} — the normal path. The phone transcribes; this
 *       cleans the transcript up.</li>
 *   <li>{@code POST /api/v1/dictation/transcribe} — the fallback for a phone that cannot
 *       transcribe: upload the recording, get the transcript and its tidied form.</li>
 * </ul>
 *
 * <p>Neither route stores anything — no audio, no transcript, no result. The client shows
 * the text to its user and saves it with {@code PUT /notes/{ref}} or {@code POST /comments}
 * once they accept it.
 *
 * <p>Both spend money at an outside service, so they sit behind {@link DictationAllowance}
 * as well as the key, and both answer 503 in an environment where that service is not
 * configured.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@RestController
@RequestMapping("/api/v1/dictation")
public class ApiDictationController {

    private static final Logger log = LoggerFactory.getLogger(ApiDictationController.class);

    /** The longest transcript accepted, in characters — the write routes' own limit. */
    static final int MAX_TEXT_CHARS = ApiAuthoringController.MAX_BODY_CHARS;

    /** The largest recording accepted, in bytes. */
    static final long MAX_AUDIO_BYTES = 10L * 1024 * 1024;

    private final TranscriptTidier   tidier;
    private final SpeechTranscriber  transcriber;
    private final DictationAllowance allowance;

    /**
     * @param aTranscriptTidier   cleans a transcript up
     * @param aSpeechTranscriber  speech to text, for the fallback route
     * @param aDictationAllowance the per-account daily cap
     */
    public ApiDictationController(final TranscriptTidier aTranscriptTidier,
                                  final SpeechTranscriber aSpeechTranscriber,
                                  final DictationAllowance aDictationAllowance) {
        this.tidier      = aTranscriptTidier;
        this.transcriber = aSpeechTranscriber;
        this.allowance   = aDictationAllowance;
    }

    /**
     * A transcript as the client sends it.
     *
     * @param text the raw transcript
     * @param lang the language spoken, a tag such as {@code fi}; optional
     */
    public record TidyIn(String text, String lang) { }

    /**
     * {@code POST /api/v1/dictation/tidy} — clean up a transcript the phone made.
     *
     * @param aTranscript the transcript
     * @param aRequest    carries the resolved key
     * @return {@code text} and whether it was {@code tidied}, or an {@link ApiError}
     */
    @PostMapping(value = "/tidy", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> tidy(@RequestBody final TidyIn aTranscript,
                                       final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        final String text = aTranscript == null ? null : aTranscript.text();
        if (text == null || text.isBlank()) return error(400, "empty_body", "text must not be empty.");
        if (text.length() > MAX_TEXT_CHARS) return error(400, "body_too_long",
            "text may be at most " + MAX_TEXT_CHARS + " characters; got " + text.length() + ".");
        if (!tidier.available()) return error(503, "tidy_unavailable",
            "Tidying is not set up on this server. Save the transcript as it is.");
        if (!allowance.tryTake(owner.getId())) return overLimit();

        final Tidied result;
        try {
            result = tidier.tidy(text.strip(), aTranscript.lang());
        } catch (final DictationException e) {
            log.warn("Tidy failed upstream", e);
            return error(502, "upstream_failed", "The tidying service did not answer. Try again, "
                + "or save the transcript as it is.");
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", result.text());
        body.put("tidied", result.tidied());
        return ok(body);
    }

    /**
     * {@code POST /api/v1/dictation/transcribe} — multipart upload of a recording, for a
     * phone that could not transcribe it itself.
     *
     * @param anAudio  the recording, form field {@code audio}
     * @param aLang    the language spoken; optional, the service detects it otherwise
     * @param aTidy    whether to clean the transcript up as well; default {@code true}
     * @param aRequest carries the resolved key
     * @return {@code transcript} as heard, {@code text} to save and whether it was
     *         {@code tidied}; or an {@link ApiError}
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> transcribe(@RequestParam("audio") final MultipartFile anAudio,
                                             @RequestParam(value = "lang", required = false) final String aLang,
                                             @RequestParam(value = "tidy", defaultValue = "true") final boolean aTidy,
                                             final HttpServletRequest aRequest) {
        final User owner = owner(aRequest);
        if (owner == null) return noKey();
        if (anAudio == null || anAudio.isEmpty()) return error(400, "bad_audio", "audio is empty.");
        if (!isAudio(anAudio.getContentType())) return error(400, "bad_audio",
            "audio must be a sound recording (audio/mp4, audio/mpeg, audio/wav, audio/webm, audio/ogg); "
            + "got " + anAudio.getContentType() + ".");
        if (anAudio.getSize() > MAX_AUDIO_BYTES) return error(413, "audio_too_large",
            "A recording may be at most " + (MAX_AUDIO_BYTES / (1024 * 1024)) + " MB.");
        if (!transcriber.available()) return error(503, "transcription_unavailable",
            "Server-side transcription is not set up. Transcribe on the device and use /dictation/tidy.");
        if (!allowance.tryTake(owner.getId())) return overLimit();

        final String transcript;
        try {
            transcript = transcriber.transcribe(anAudio.getBytes(), anAudio.getOriginalFilename(),
                                                anAudio.getContentType(), aLang);
        } catch (final IOException | DictationException e) {
            log.warn("Transcription failed", e);
            return error(502, "upstream_failed", "The transcription service did not answer. Try again.");
        }

        Tidied result = new Tidied(transcript, false);
        // A recording with nothing said has nothing to tidy, and one longer than a note may be
        // is returned as heard; an unconfigured or failing tidier must not cost the user the
        // transcript they already have.
        if (aTidy && !transcript.isBlank() && transcript.length() <= MAX_TEXT_CHARS && tidier.available()) {
            try {
                result = tidier.tidy(transcript, aLang);
            } catch (final DictationException e) {
                log.warn("Tidy failed upstream after transcription; transcript returned as heard", e);
            }
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("transcript", transcript);
        body.put("text", result.text());
        body.put("tidied", result.tidied());
        return ok(body);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Phones label recordings loosely: m4a arrives as audio/mp4, video/mp4 or octet-stream. */
    static boolean isAudio(final String aContentType) {
        if (aContentType == null) return false;
        final String type = aContentType.toLowerCase(Locale.ROOT);
        return type.startsWith("audio/") || type.startsWith("video/mp4") || type.startsWith("video/webm")
            || type.startsWith("application/octet-stream");
    }

    private static User owner(final HttpServletRequest aRequest) {
        final ResolvedKey key = (ResolvedKey) aRequest.getAttribute(ApiAuthFilter.ATTR_KEY);
        final User owner = key == null ? null : key.owner();
        return owner == null || owner.getId() == null ? null : owner;
    }

    private static ResponseEntity<Object> ok(final Map<String, Object> aBody) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(aBody);
    }

    private static ResponseEntity<Object> noKey() {
        return error(401, "missing_key", "Dictation needs the key of the account that dictates.");
    }

    private ResponseEntity<Object> overLimit() {
        return error(429, "dictation_limit", "This account has used its " + allowance.perDay()
            + " dictation calls for today (UTC). The transcript can still be saved as it is.");
    }

    private static ResponseEntity<Object> error(final int aStatus, final String aSlug, final String aMessage) {
        return ResponseEntity.status(aStatus).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(aStatus, aSlug, aMessage, "dictation"));
    }
}
