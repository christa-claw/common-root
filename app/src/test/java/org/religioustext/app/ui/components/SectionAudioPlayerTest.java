// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.components;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SectionAudioPlayerTest {

    @Test
    void anAboutAudioPathYieldsItsLocaleAndSection() {
        assertThat(SectionAudioPlayer.aboutPath("/audio/about/fi/hero.mp3")).containsExactly("fi", "hero");
        assertThat(SectionAudioPlayer.aboutPath("/audio/about/zh-Hant/how_to.mp3")).containsExactly("zh-Hant", "how_to");
    }

    @Test
    void anythingElseYieldsNothingSoNoEventIsAttached() {
        assertThat(SectionAudioPlayer.aboutPath(null)).isNull();
        assertThat(SectionAudioPlayer.aboutPath("/audio/bible-kjv-1611/en-US/GEN/01_GEN.mp3")).isNull();
        assertThat(SectionAudioPlayer.aboutPath("/audio/about/fi/hero.wav")).isNull();
        assertThat(SectionAudioPlayer.aboutPath("/audio/about/fi/../../x.mp3")).isNull();
        assertThat(SectionAudioPlayer.aboutPath("https://evil.example/audio/about/fi/hero.mp3")).isNull();
    }
}
