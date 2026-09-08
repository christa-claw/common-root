// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.religioustext.app.service.UserService;

/**
 * Handles the email-verification link: /verify?token=XYZ.
 * Looks up the one-time token, marks the account verified, redirects to
 * /login?verified so the LoginView shows a confirmation notification.
 */
@Route("verify")
@PageTitle("Verify Email — Common Root?")
@AnonymousAllowed
public class VerifyView extends VerticalLayout implements BeforeEnterObserver {

    private final UserService userService;

    public VerifyView(final UserService userService) {
        this.userService = userService;
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        final var params = event.getLocation().getQueryParameters().getParameters();
        final var tokens = params.get("token");

        if (tokens == null || tokens.isEmpty()) {
            showError("No verification token supplied.");
            return;
        }

        final String token = tokens.get(0);
        if (userService.verifyEmail(token)) {
            // Token valid — redirect to login with a success flag.
            event.forwardTo("login?verified");
        } else {
            showError("This verification link has already been used or is invalid."
                + " If you're having trouble signing in, please register again.");
        }
    }

    private void showError(final String msg) {
        final H2 heading = new H2("Verification failed");
        final Paragraph body = new Paragraph(msg);
        body.getStyle().set("color", "var(--lumo-secondary-text-color)");
        final Anchor back = new Anchor("/register", "Back to registration");
        add(heading, body, back);
    }
}
