// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.WrappedSession;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Reports a successful sign-in to Umami as {@code login-success}.
 *
 * <p>The sign-in is a plain form POST to Spring Security, which answers with a redirect:
 * no Vaadin UI exists at that moment, so there is nowhere to send a browser event from.
 * So it happens in two steps. Spring publishes {@link InteractiveAuthenticationSuccessEvent}
 * on a form login; {@link #onLogin} marks the session. The next page the person lands on
 * (a full load, after the redirect) passes through {@code AfterNavigation}, where the mark
 * is consumed and the event sent once the tracker has loaded.
 *
 * <p>Nothing about the person is recorded: no name, no address, no id.
 */
@Component
public class LoginAnalytics implements VaadinServiceInitListener {

    /** The session attribute that carries "this session just signed in" to the next page. */
    static final String FLAG = "cr.loginSuccess";

    /** Mark the session of the request that just authenticated. */
    @EventListener
    public void onLogin(final InteractiveAuthenticationSuccessEvent anEvent) {
        final RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) request.setAttribute(FLAG, Boolean.TRUE, RequestAttributes.SCOPE_SESSION);
    }

    @Override
    public void serviceInit(final ServiceInitEvent anEvent) {
        anEvent.getSource().addUIInitListener(init -> {
            final UI ui = init.getUI();
            ui.addAfterNavigationListener(nav -> {
                if (consumeFlag()) Analytics.trackWhenReady(ui, "login-success");
            });
        });
    }

    /** True once per sign-in: reads the mark and removes it, so a reload does not report it again. */
    private static boolean consumeFlag() {
        final VaadinSession vaadin = VaadinSession.getCurrent();
        final WrappedSession session = vaadin == null ? null : vaadin.getSession();
        if (session == null || session.getAttribute(FLAG) == null) return false;
        session.removeAttribute(FLAG);
        return true;
    }
}
