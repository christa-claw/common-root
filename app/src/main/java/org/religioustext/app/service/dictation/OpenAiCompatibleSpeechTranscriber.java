// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service.dictation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * {@link SpeechTranscriber} over the {@code POST {base}/audio/transcriptions} multipart
 * contract ({@code file}, {@code model}, optional {@code language}; answer {@code {"text": …}})
 * — the shape hosted Whisper services and self-hosted Whisper servers alike accept, so the
 * provider is a matter of configuration, not code.
 *
 * <p>Its own {@link RestTemplate}: the shared bean carries the BaseX credentials, which must
 * never travel to an outside host.
 *
 * <p>Off unless {@code dictation.speech.base-url} is set; no audio leaves the server otherwise.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@Service
public class OpenAiCompatibleSpeechTranscriber implements SpeechTranscriber {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final RestTemplate http;

    /**
     * @param aBaseUrl the service's API base, up to but not including {@code /audio/transcriptions};
     *                 blank leaves transcription off
     * @param anApiKey the bearer credential, or blank for a service that needs none
     * @param aModel   the speech model's name
     * @param aBuilder builds the dedicated client
     */
    public OpenAiCompatibleSpeechTranscriber(
            @Value("${dictation.speech.base-url:}") final String aBaseUrl,
            @Value("${dictation.speech.api-key:}") final String anApiKey,
            @Value("${dictation.speech.model:whisper-1}") final String aModel,
            final RestTemplateBuilder aBuilder) {
        this.baseUrl = aBaseUrl == null ? "" : aBaseUrl.trim().replaceAll("/+$", "");
        this.apiKey  = anApiKey == null ? "" : anApiKey.trim();
        this.model   = aModel;
        this.http    = aBuilder.setConnectTimeout(Duration.ofSeconds(10))
                               .setReadTimeout(Duration.ofSeconds(120)).build();
    }

    @Override
    public boolean available() { return !baseUrl.isEmpty(); }

    /** {@inheritDoc} */
    @Override
    public String transcribe(final byte[] theAudio, final String aFilename, final String aContentType,
                             final String aLanguageTag) {
        if (!available()) throw new IllegalStateException("Transcription is not configured");
        final HttpHeaders fileHeaders = new HttpHeaders();
        if (aContentType != null && !aContentType.isBlank()) {
            try { fileHeaders.setContentType(MediaType.parseMediaType(aContentType)); }
            catch (final IllegalArgumentException e) { /* the file name's extension is enough */ }
        }
        final String filename = aFilename == null || aFilename.isBlank() ? "note.m4a" : aFilename;
        final MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(new ByteArrayResource(theAudio) {
            @Override public String getFilename() { return filename; }
        }, fileHeaders));
        form.add("model", model);
        form.add("response_format", "json");
        if (aLanguageTag != null && !aLanguageTag.isBlank()) form.add("language", primary(aLanguageTag));

        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (!apiKey.isEmpty()) headers.setBearerAuth(apiKey);
        try {
            final Map<?, ?> answer = http.postForObject(baseUrl + "/audio/transcriptions",
                new HttpEntity<>(form, headers), Map.class);
            final Object text = answer == null ? null : answer.get("text");
            return text == null ? "" : text.toString().strip();
        } catch (final RestClientException e) {
            throw new DictationException("Speech transcription call failed", e);
        }
    }

    /** {@code fi-FI} as {@code fi}: the contract takes the bare language code. */
    static String primary(final String aLanguageTag) {
        final String tag = aLanguageTag.trim().toLowerCase(java.util.Locale.ROOT);
        final int dash = tag.indexOf('-');
        return dash > 0 ? tag.substring(0, dash) : tag;
    }
}
