// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.UI;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs the Strong's tooltip's client script ({@code strongs-tip.js}) into the page. The script
 * guards itself, so installing it on every attach of the reader is harmless; it listens on the
 * document, so numbers rendered later need nothing.
 */
final class StrongsTip {

    private static volatile String script;

    /** @param aHost the reader view's element: a double-click on a number calls its
     *  {@code openStrongs} client-callable. Re-assigned on every attach, so a new view takes over. */
    static void install(final UI aUi, final com.vaadin.flow.dom.Element aHost) {
        if (aUi == null) return;
        aUi.getPage().executeJs("window.__strongsHost = $0;", aHost);
        aUi.getPage().executeJs(script());
    }

    static String script() {
        String s = script;
        if (s == null) {
            try (InputStream in = StrongsTip.class.getResourceAsStream("/strongs-tip.js")) {
                if (in == null) throw new IllegalStateException("strongs-tip.js missing from the classpath");
                s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            script = s;
        }
        return s;
    }

    private StrongsTip() { }
}
