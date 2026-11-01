// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.UI;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs the arrow-key navigation script ({@code reader-keys.js}) into the page. Same shape as
 * {@link StrongsTip}: the script guards itself, so installing it on every attach is harmless.
 */
final class ReaderKeys {

    private static volatile String script;

    /** @param aHost the reader view's element: the script calls its {@code stepChapterKey}
     *  client-callable. Re-assigned on every attach, so a new view takes over. */
    static void install(final UI aUi, final com.vaadin.flow.dom.Element aHost) {
        if (aUi == null) return;
        aUi.getPage().executeJs("window.__readerKeysHost = $0;", aHost);
        aUi.getPage().executeJs(script());
    }

    static String script() {
        String s = script;
        if (s == null) {
            try (InputStream in = ReaderKeys.class.getResourceAsStream("/reader-keys.js")) {
                if (in == null) throw new IllegalStateException("reader-keys.js missing from the classpath");
                s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            script = s;
        }
        return s;
    }

    private ReaderKeys() { }
}
