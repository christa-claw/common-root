// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service.dictation;

/**
 * Speech to text on the server — the fallback for a phone that cannot transcribe on its own.
 * The seam between the dictation route and whatever speech service is configured.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
public interface SpeechTranscriber {

    /** @return whether server-side transcription is configured in this environment */
    boolean available();

    /**
     * @param theAudio      the recording
     * @param aFilename     its file name; the extension tells the service the container format
     * @param aContentType  its media type ({@code audio/mp4}), or {@code null}
     * @param aLanguageTag  the language spoken, or {@code null} to let the service detect it
     * @return the transcript, possibly empty when nothing was said
     * @throws DictationException if the service could not be reached or failed
     */
    String transcribe(byte[] theAudio, String aFilename, String aContentType, String aLanguageTag);
}
