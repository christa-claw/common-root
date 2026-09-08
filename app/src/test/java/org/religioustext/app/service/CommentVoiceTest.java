// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.CommentQueryService.VerseComment;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The voice rules behind comment muting (V17).
 *
 *  <p>These are pinned rather than assumed because two of them are invariants a
 *  future change could quietly break without any test failing elsewhere: a
 *  reader's OWN comments must survive muting (hiding your own drafts reads as
 *  data loss), and an unattributable comment must never match a mute (there is
 *  no handle to mute it by, and inventing one from the email would publish an
 *  address). The third is that one definition of "whose comment is this" serves
 *  both the inline bubbles and the panel — they disagreed once, when only the
 *  panel knew about channels.
 */
class CommentVoiceTest {

    private static VerseComment comment(final String aContent, final String aWatchLabel,
                                        final String anAuthor, final boolean anOwnFlag) {
        return new VerseComment("cmt_1", aContent, null, null, aWatchLabel, null,
                                List.of(), anOwnFlag, false, anAuthor);
    }

    @Test
    void watchLabelIsTheVoiceWhenPresent() {
        assertThat(CommentQueryService.voiceOf(
            comment("Anything at all", "Shamounian Explains", "Someone Else", false)))
            .isEqualTo("Shamounian Explains");
    }

    /** Seeded arguments predating the label carried the channel inline. */
    @Test
    void bracketPrefixIsTheVoiceWhenThereIsNoLabel() {
        assertThat(CommentQueryService.voiceOf(
            comment("[Apologia Studios] The argument runs…", null, null, false)))
            .isEqualTo("Apologia Studios");
        assertThat(CommentQueryService.voiceOf(
            comment("[Dr Zakir Naik — 2019] Point one", null, null, false)))
            .isEqualTo("Dr Zakir Naik");
    }

    @Test
    void authorIsTheVoiceForAPersonsOwnWords() {
        assertThat(CommentQueryService.voiceOf(comment("A plain remark", null, "Aino K.", false)))
            .isEqualTo("Aino K.");
    }

    @Test
    void anUnattributableCommentHasNoVoice() {
        assertThat(CommentQueryService.voiceOf(comment("A plain remark", null, null, false)))
            .isNull();
        assertThat(CommentQueryService.voiceOf(comment("A plain remark", "  ", "  ", false)))
            .isNull();
    }

    /** Newline separated on purpose: channel names contain commas and
     *  apostrophes ("DAWAH BRO'S PODCAST") but never a line break. */
    @Test
    void muteListSurvivesAwkwardNames() {
        final List<String> voices = List.of("DAWAH BRO'S PODCAST", "Hatun Tash, DCCI");
        final String stored = CommentQueryService.formatMuted(voices);
        assertThat(CommentQueryService.parseMuted(stored))
            .containsExactly("DAWAH BRO'S PODCAST", "Hatun Tash, DCCI");
    }

    @Test
    void emptyMuteListStoresAsNull() {
        assertThat(CommentQueryService.formatMuted(List.of())).isNull();
        assertThat(CommentQueryService.formatMuted(List.of("   "))).isNull();
        assertThat(CommentQueryService.parseMuted(null)).isEmpty();
        assertThat(CommentQueryService.parseMuted("  ")).isEmpty();
    }

    @Test
    void aMutedVoiceIsHidden() {
        assertThat(CommentQueryService.isMuted(
            comment("x", "Vlad Savchuk", null, false), Set.of("Vlad Savchuk"))).isTrue();
        assertThat(CommentQueryService.isMuted(
            comment("x", "Vlad Savchuk", null, false), Set.of("Ali Dawah"))).isFalse();
    }

    /** The invariant: muting a voice you also write as must not hide you from
     *  yourself — including private drafts, which exist nowhere else. */
    @Test
    void yourOwnCommentsAreNeverMuted() {
        assertThat(CommentQueryService.isMuted(
            comment("x", null, "Christa", true), Set.of("Christa"))).isFalse();
    }

    @Test
    void anUnattributableCommentIsNeverMuted() {
        assertThat(CommentQueryService.isMuted(
            comment("x", null, null, false), Set.of("Christa", "Ali Dawah"))).isFalse();
    }

    @Test
    void anEmptyMuteListHidesNothing() {
        assertThat(CommentQueryService.isMuted(
            comment("x", "Ali Dawah", null, false), Set.of())).isFalse();
        assertThat(CommentQueryService.isMuted(
            comment("x", "Ali Dawah", null, false), null)).isFalse();
    }
}
