// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import java.util.function.LongSupplier;

/**
 * Decides when the reader may store "where I left off": not while it is still
 * rebuilding its columns, not the same position twice, and not more often than
 * once every {@value #MIN_INTERVAL_MS} ms.
 *
 * <p>Why the hold exists: opening a link (a shared one, or the reader's own
 * saved position on resume) first picks each column's edition, which opens it
 * at its start (Genesis 1), and only then turns to the linked chapter. Every
 * one of those steps refreshes the copy link, and with it the saved position.
 * Unheld, the Genesis 1 step was the first save, and the throttle then dropped
 * the save of the chapter actually shown, so a reader who resumed at John 4
 * had Genesis 1 stored over it, and the phone app, which syncs that position,
 * followed. {@link ReaderView} holds the saver for the whole rebuild and saves
 * once afterwards.
 *
 * <p>Not thread-safe; one instance per reader view, used on its UI thread.
 *
 * @author Christa Claw
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 * @see ReaderView
 */
final class PositionSaver {

    /** The shortest gap between two stored positions, in milliseconds. */
    static final long MIN_INTERVAL_MS = 5000;

    private final LongSupplier clock;
    private boolean held;
    private boolean saved;
    private long lastSaveMs;
    private String lastQuery;

    /**
     * @param aClock the current time in milliseconds; {@code System::currentTimeMillis}
     *               in the view, a fake in tests
     */
    PositionSaver(final LongSupplier aClock) {
        this.clock = aClock;
    }

    /** Stops every save until {@link #release()}: the columns are being rebuilt. */
    void hold() {
        held = true;
    }

    /** Lets saves through again once the rebuilt columns show where they belong. */
    void release() {
        held = false;
    }

    /**
     * Whether {@code aQuery} should be stored now, and if so, records it as stored.
     *
     * @param aQuery the reader-link query string for what is shown, or {@code null}
     *               when no column is configured
     * @return {@code true} when the caller should store it: not held, not
     *         {@code null}, different from the last one stored, and at least
     *         {@value #MIN_INTERVAL_MS} ms after it
     */
    boolean shouldSave(final String aQuery) {
        if (held || aQuery == null || aQuery.equals(lastQuery)) return false;
        final long now = clock.getAsLong();
        if (saved && now - lastSaveMs < MIN_INTERVAL_MS) return false;
        saved = true;
        lastSaveMs = now;
        lastQuery = aQuery;
        return true;
    }
}
