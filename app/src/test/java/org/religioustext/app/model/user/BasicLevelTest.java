// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The reduced Documentum permission ladder; delete is top, read is the owner floor (§6). */
class BasicLevelTest {

    @Test
    void ladderIsAscendingWithDeleteOnTop() {
        assertThat(BasicLevel.none.ordinal()).isLessThan(BasicLevel.browse.ordinal());
        assertThat(BasicLevel.browse.ordinal()).isLessThan(BasicLevel.read.ordinal());
        assertThat(BasicLevel.read.ordinal()).isLessThan(BasicLevel.write.ordinal());
        assertThat(BasicLevel.write.ordinal()).isLessThan(BasicLevel.delete.ordinal());
    }

    @Test
    void ownerFloorIsRead() {
        assertThat(BasicLevel.OWNER_FLOOR).isEqualTo(BasicLevel.read);
    }

    @Test
    void deleteImpliesWriteReadBrowse() {
        assertThat(BasicLevel.delete.atLeast(BasicLevel.write)).isTrue();
        assertThat(BasicLevel.delete.atLeast(BasicLevel.read)).isTrue();
        assertThat(BasicLevel.write.atLeast(BasicLevel.delete)).isFalse();
    }

    @Test
    void maxPicksHigher() {
        assertThat(BasicLevel.max(BasicLevel.read, BasicLevel.write)).isEqualTo(BasicLevel.write);
        assertThat(BasicLevel.max(BasicLevel.delete, BasicLevel.none)).isEqualTo(BasicLevel.delete);
        assertThat(BasicLevel.max(BasicLevel.read, BasicLevel.read)).isEqualTo(BasicLevel.read);
    }
}
