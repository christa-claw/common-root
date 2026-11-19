// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PositionSaver}: the hold that keeps a resumed reader's position from being
 * overwritten with the edition's start, the throttle and the repeat check.
 *
 * @author Christa Claw
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
class PositionSaverTest {

    private static final String GEN_1 = "c1.src=kjv&c1.ref=GEN.1";
    private static final String JHN_4 = "c1.src=kjv&c1.ref=JHN.4";

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final PositionSaver saver = new PositionSaver(now::get);

    @Test
    void resumeSavesTheChapterShownNotTheEditionsStart() {
        // what applyLink does: the source pick lands on GEN 1, then the link's chapter opens
        saver.hold();
        assertThat(saver.shouldSave(GEN_1)).isFalse();
        now.addAndGet(200);
        assertThat(saver.shouldSave(JHN_4)).isFalse();
        saver.release();
        // the one save afterwards is the chapter on screen
        assertThat(saver.shouldSave(JHN_4)).isTrue();
    }

    @Test
    void firstSaveGoesThroughAtOnce() {
        assertThat(saver.shouldSave(JHN_4)).isTrue();
    }

    @Test
    void savesAreAtLeastTheIntervalApart() {
        assertThat(saver.shouldSave(GEN_1)).isTrue();
        now.addAndGet(PositionSaver.MIN_INTERVAL_MS - 1);
        assertThat(saver.shouldSave(JHN_4)).isFalse();
        now.addAndGet(1);
        assertThat(saver.shouldSave(JHN_4)).isTrue();
    }

    @Test
    void theSamePositionIsNotSavedTwice() {
        assertThat(saver.shouldSave(JHN_4)).isTrue();
        now.addAndGet(PositionSaver.MIN_INTERVAL_MS * 3);
        assertThat(saver.shouldSave(JHN_4)).isFalse();
    }

    @Test
    void nothingToSaveWithoutAColumn() {
        assertThat(saver.shouldSave(null)).isFalse();
        // and a refused null does not start the interval
        assertThat(saver.shouldSave(JHN_4)).isTrue();
    }
}
