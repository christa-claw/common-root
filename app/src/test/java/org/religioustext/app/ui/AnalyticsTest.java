// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnalyticsTest {

    @Test
    void anEventNamesItsPropertiesInTheScriptAndBindsTheValuesAsParameters() {
        final Analytics.Call c = Analytics.build("xref-open", "edition", "King James Version", "count", 61);

        assertThat(c.script()).isEqualTo("window.umami && window.umami.track($0, {edition: $1, count: $2})");
        assertThat(c.params()).containsExactly("xref-open", "King James Version", 61);
    }

    @Test
    void aValueIsNeverWrittenIntoTheScriptText() {
        final Analytics.Call c = Analytics.build("e", "source", "Genesis 1:1'}); evil({");

        assertThat(c.script()).doesNotContain("evil").doesNotContain("Genesis");
        assertThat(c.params()[1]).isEqualTo("Genesis 1:1'}); evil({");
    }

    @Test
    void aPropertyWithNoValueIsLeftOutAndTheParametersStayContiguous() {
        final Analytics.Call c = Analytics.build("search-run", "hits", 0, "query", null, "length", 5);

        assertThat(c.script()).isEqualTo("window.umami && window.umami.track($0, {hits: $1, length: $2})");
        assertThat(c.params()).containsExactly("search-run", 0, 5);
    }

    @Test
    void anEventWithNoPropertiesStillSends() {
        final Analytics.Call c = Analytics.build("ping");

        assertThat(c.script()).isEqualTo("window.umami && window.umami.track($0, {})");
        assertThat(c.params()).containsExactly("ping");
    }

    @Test
    void aPropertyNameThatIsNotAnIdentifierIsRefused() {
        assertThatThrownBy(() -> Analytics.build("e", "bad key", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Analytics.build("e", "a:$0}); evil(", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Analytics.build("e", "1st", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEventRaisedDuringPageLoadWaitsForTheTrackerAndGivesUpAfterTenSeconds() {
        final Analytics.Call c = Analytics.buildWhenReady("login-failure", "reason", "bad-password");

        assertThat(c.script())
            .startsWith("(function send(n) { if (window.umami) { window.umami.track($0, {reason: $1}); }")
            .contains("n !== 50")
            .contains("setTimeout(function () { send(n + 1); }, 200)")
            .endsWith("})(0);");
        assertThat(c.params()).containsExactly("login-failure", "bad-password");
    }

    @Test
    void aDebouncedEventReplacesItsPendingOneAndSendsAfterTheDelay() {
        final Analytics.Call c = Analytics.buildDebounced("search", 1500, "search-run", "hits", 3);

        assertThat(c.script())
            .contains("clearTimeout(window.__crDeb.search)")
            .contains("window.__crDeb.search = setTimeout(")
            .contains("window.umami.track($0, {hits: $1})")
            .endsWith(", 1500);");
        assertThat(c.params()).containsExactly("search-run", 3);
    }

    @Test
    void aTimerKeyThatIsNotAnIdentifierIsRefused() {
        assertThatThrownBy(() -> Analytics.buildDebounced("a'];evil();//", 10, "e"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEventQueuedForTheNextPageNumbersItsParametersFromTheGivenOffset() {
        final Analytics.Call c = Analytics.queueForNextPage(2, "ui-language-change", "from", "en", "to", "fi");

        assertThat(c.script())
            .contains("sessionStorage.setItem('cr.pendingEvent'")
            .contains("name: $2, data: {from: $3, to: $4}");
        assertThat(c.params()).containsExactly("ui-language-change", "en", "fi");
    }

    @Test
    void theReplayScriptReadsAndClearsTheSameKeyItIsQueuedUnder() {
        assertThat(Analytics.REPLAY_SCRIPT)
            .contains("sessionStorage.getItem('" + Analytics.PENDING_KEY + "')")
            .contains("sessionStorage.removeItem('" + Analytics.PENDING_KEY + "')")
            .contains("window.umami.track(ev.name,ev.data)")
            .startsWith("<script>").endsWith("</script>");
    }

    @Test
    void theReplayScriptHasNoCharacterTheAppShellWouldHtmlEscape() {
        // Vaadin parses inline content as HTML and writes it back escaped: "&&" arrived in the
        // browser as "&amp;&amp;", a syntax error on every page. Keep & < > out of the body.
        final String body = Analytics.REPLAY_SCRIPT
            .substring("<script>".length(), Analytics.REPLAY_SCRIPT.length() - "</script>".length());

        assertThat(body).doesNotContain("&").doesNotContain("<").doesNotContain(">");
    }
}
