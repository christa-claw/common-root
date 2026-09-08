package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.religioustext.app.ui.views.ReaderView.channelParam;

/**
 * The {@code ?comments=} grammar. The sentinel distinguishes "panel open, no commenter
 * chosen" from "panel open, filtered to X" — without it an open, unfiltered panel simply
 * vanished from the link (and from the post-login return).
 */
class ReaderViewLinkTest {

    @Test
    void sentinelMeansOpenWithNoFilter() {
        assertThat(channelParam(ReaderView.COMMENTS_ALL)).isNull();
    }

    @Test
    void legacyAsteriskSentinelStillUnderstood() {
        // '*' was the first cut; links copied before the change must keep working.
        assertThat(channelParam("*")).isNull();
    }

    @Test
    void blankAndNullMeanNoFilter() {
        assertThat(channelParam(null)).isNull();
        assertThat(channelParam("")).isNull();
        assertThat(channelParam("   ")).isNull();
    }

    @Test
    void aRealChannelNameIsPassedThrough() {
        assertThat(channelParam("Ali Dawah")).isEqualTo("Ali Dawah");
        assertThat(channelParam("The Crucible")).isEqualTo("The Crucible");
    }

    @Test
    void sentinelUsesOnlyUnreservedUrlCharacters() {
        // The bug this guards: a sub-delimiter sentinel did not survive the Location parse
        // and QueryParameters re-serialisation of the post-login forward.
        assertThat(ReaderView.COMMENTS_ALL).matches("[A-Za-z0-9_.~-]+");
    }
}
