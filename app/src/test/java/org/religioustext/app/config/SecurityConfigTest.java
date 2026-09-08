package org.religioustext.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.religioustext.app.config.SecurityConfig.logoutTarget;

/**
 * Post-logout landing. Signing out in the reader keeps you reading (no account required);
 * everything else goes to the landing page. The referer is attacker-influencable, so the
 * same-origin + {@code /reader} constraints are the open-redirect guard, not a nicety.
 */
class SecurityConfigTest {

    private static final String HOST = "common-root.org";

    @Test
    void readerRefererIsHonouredWithItsQueryString() {
        assertThat(logoutTarget("https://common-root.org/reader?cols=2&c0.src=niv", HOST))
            .isEqualTo("/reader?cols=2&c0.src=niv");
    }

    @Test
    void readerRefererWithoutQueryKeepsThePath() {
        assertThat(logoutTarget("https://common-root.org/reader", HOST)).isEqualTo("/reader");
    }

    @Test
    void relativeRefererIsSameOriginByDefinition() {
        assertThat(logoutTarget("/reader?cols=1", HOST)).isEqualTo("/reader?cols=1");
    }

    @Test
    void nonReaderPagesFallBackToRoot() {
        assertThat(logoutTarget("https://common-root.org/", HOST)).isEqualTo("/");
        assertThat(logoutTarget("https://common-root.org/profile", HOST)).isEqualTo("/");
    }

    @Test
    void foreignHostIsRefused() {
        // The open-redirect attempt: a reader-shaped path on someone else's origin.
        assertThat(logoutTarget("https://evil.example/reader?x=1", HOST)).isEqualTo("/");
    }

    @Test
    void hostComparisonIsCaseInsensitive() {
        assertThat(logoutTarget("https://COMMON-ROOT.ORG/reader", HOST)).isEqualTo("/reader");
    }

    @Test
    void missingOrMalformedRefererFallsBackToRoot() {
        assertThat(logoutTarget(null, HOST)).isEqualTo("/");
        assertThat(logoutTarget("   ", HOST)).isEqualTo("/");
        assertThat(logoutTarget("ht tp://broken uri", HOST)).isEqualTo("/");
    }

    @Test
    void readerPrefixDoesNotLeakToOtherPaths() {
        // Guard the startsWith: only real reader paths, not a lookalike elsewhere.
        assertThat(logoutTarget("https://evil.example/reader", HOST)).isEqualTo("/");
    }
}
