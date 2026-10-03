// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.model.DisplayOptions.DisplayMode;
import org.religioustext.app.model.DisplayOptions.OrderMode;
import org.religioustext.app.model.user.UserPreferences;
import org.religioustext.app.service.UserPreferencesService;
import org.religioustext.app.ui.views.ReaderLink;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the print designer (a static page, {@code edition-designer.html}) should
 * start from for the person looking at it.
 *
 * <p>A signed-in reader has already said which edition, reading order and display
 * mode they like, in Preferences; the designer should open on those rather than
 * on its own defaults. The page asks here, and anything it was already told by
 * its URL (a reader hand-off, or the Buy button on an edition page) wins over
 * what comes back. A signed-out visitor gets an empty object, which is not an
 * error: the designer simply keeps its defaults.
 *
 * <p>Values are in the designer's own vocabulary ({@code ordering} is canonical /
 * chronological / tanakh / writing; {@code mode} is a DisplayMode constant), so
 * the page does no translating. Nothing here is secret, but it is per-user, so
 * it is never cached.
 */
@RestController
public class DesignerDefaultsController {

    private final UserPreferencesService prefsService;

    public DesignerDefaultsController(final UserPreferencesService aPrefsService) {
        this.prefsService = aPrefsService;
    }

    @GetMapping(value = "/designer/defaults", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> defaults(final Principal aPrincipal) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (aPrincipal != null) {
            final UserPreferences p = prefsService.find(aPrincipal.getName()).orElse(null);
            if (p != null) {
                if (p.getDefaultSource() != null && !p.getDefaultSource().isBlank())
                    out.put("source", p.getDefaultSource().toUpperCase(Locale.ROOT));
                if (p.getDefaultOrder() != null) {
                    final OrderMode order = ReaderLink.orderFromToken(p.getDefaultOrder());
                    out.put("ordering", order.name().toLowerCase(Locale.ROOT));
                }
                final DisplayMode mode = ReaderLink.modeFromToken(p.getDefaultMode());
                if (mode != null) out.put("mode", mode.name());
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
