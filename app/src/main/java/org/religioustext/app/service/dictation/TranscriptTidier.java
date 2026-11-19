// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service.dictation;

/**
 * Turns a raw speech transcript into text fit to save as a note or comment: fillers and false
 * starts removed, punctuation and paragraphs restored — and nothing added, translated or
 * summarised. The seam between the dictation route and whatever model does the tidying.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
public interface TranscriptTidier {

    /**
     * The outcome of a tidy.
     *
     * @param text   the tidied text, or the transcript unchanged when {@code tidied} is false
     * @param tidied {@code false} when the model declined or returned nothing usable, so the
     *               caller got its own words back rather than an error
     */
    record Tidied(String text, boolean tidied) { }

    /** @return whether tidying is configured in this environment */
    boolean available();

    /**
     * @param aTranscript    the raw transcript; never blank
     * @param aLanguageTag   the language spoken ({@code fi}, {@code en}), or {@code null} when unknown
     * @return the tidied text
     * @throws DictationException if the model could not be reached or failed
     */
    Tidied tidy(String aTranscript, String aLanguageTag);
}
