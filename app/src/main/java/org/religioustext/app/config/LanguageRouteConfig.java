// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.config;

import com.vaadin.flow.router.RouteConfiguration;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.ui.views.AboutView;
import org.religioustext.app.ui.views.ReaderView;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Registers language-specific URL paths as dynamic Vaadin routes, so both
 * formats land on the same views with identical query-string handling:
 *
 * - {@code /} and {@code /?lang=fi} - landing page (query param, legacy)
 * - {@code /fi} - landing page (path-based, SEO-friendly)
 * - {@code /reader?lang=he} and {@code /reader/he} - the reader
 *
 * These must be Vaadin routes, not Spring MVC forwards: the Vaadin servlet is
 * mapped to {@code /*} and answers "route not found" before Spring MVC's view
 * controllers would ever see the request.
 *
 * The routed languages derive from {@link LocaleUtil#LOCALES} — the list the
 * language dropdown uses — so adding a locale there automatically adds its
 * routes here (and its security whitelist entry in {@link SecurityConfig}).
 * {@code toLanguageTag()} avoids the legacy "iw" that {@code getLanguage()}
 * yields for Hebrew. {@code LocaleInitListener} applies the language a path
 * names; {@code SocialPreviewInitListener} localises the link-preview tags.
 *
 * Both target views are {@code @AnonymousAllowed}, which the aliases inherit.
 */
@Component
public class LanguageRouteConfig implements VaadinServiceInitListener {

    @Override
    public void serviceInit(final ServiceInitEvent anEvent) {
        final RouteConfiguration routes = RouteConfiguration.forApplicationScope();
        for (final Locale locale : LocaleUtil.LOCALES) {
            final String lang = locale.toLanguageTag();
            routes.setRoute(lang, AboutView.class);
            routes.setRoute("reader/" + lang, ReaderView.class);
        }
    }
}
