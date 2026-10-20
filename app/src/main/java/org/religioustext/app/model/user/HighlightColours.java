// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The highlight palette: stored key → CSS colour. The colours are translucent so the same value
 * reads as a highlighter pen over both the light and the dark reader theme — the text colour is
 * never touched, only tinted behind.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
public final class HighlightColours {

    /** Key → CSS background, in the order the editor offers them. */
    public static final Map<String, String> CSS = new LinkedHashMap<>();
    static {
        CSS.put("yellow", "rgba(255, 214, 0, 0.38)");
        CSS.put("green",  "rgba(76, 175, 80, 0.32)");
        CSS.put("blue",   "rgba(66, 165, 245, 0.32)");
        CSS.put("pink",   "rgba(240, 98, 146, 0.32)");
        CSS.put("orange", "rgba(255, 152, 0, 0.36)");
    }

    private HighlightColours() { }

    public static boolean isValid(final String aKey) { return aKey != null && CSS.containsKey(aKey); }
}
