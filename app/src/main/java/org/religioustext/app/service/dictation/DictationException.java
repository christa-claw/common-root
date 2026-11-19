// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service.dictation;

/**
 * An outside speech or language service failed or could not be reached. The message is for
 * the log, never for the client: it may name the upstream service.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
public class DictationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param aMessage what failed
     * @param aCause   the upstream error
     */
    public DictationException(final String aMessage, final Throwable aCause) {
        super(aMessage, aCause);
    }
}
