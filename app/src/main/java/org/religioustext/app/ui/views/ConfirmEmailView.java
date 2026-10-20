// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.spring.security.AuthenticationContext;
import org.religioustext.app.service.UserService;

/**
 * Handles the email-change confirmation link: /confirm-email?token=XYZ, mailed to the NEW address.
 *
 * On success the sign-in address has changed, so any session still open under the old address is
 * stale (the security principal IS the email). The page says so and offers one button that signs
 * that session out (or just goes to the login page when no one is signed in).
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
@Route("confirm-email")
@PageTitle("Confirm Email — Common Root?")
@AnonymousAllowed
public class ConfirmEmailView extends VerticalLayout implements BeforeEnterObserver {

    private final UserService           userService;
    private final AuthenticationContext authContext;

    public ConfirmEmailView(final UserService aUserService, final AuthenticationContext anAuthContext) {
        this.userService = aUserService;
        this.authContext = anAuthContext;
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent anEvent) {
        final var tokens = anEvent.getLocation().getQueryParameters().getParameters().get("token");
        if (tokens != null && !tokens.isEmpty() && userService.confirmEmailChange(tokens.get(0))) {
            final Button signIn = new Button("Sign in with your new address", e -> {
                if (authContext.isAuthenticated()) authContext.logout();
                else getUI().ifPresent(ui -> ui.navigate("login"));
            });
            signIn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            add(new H2("Email address updated"),
                new Paragraph("Your sign-in address has changed. Please sign in again with the new address."),
                signIn);
        } else {
            add(new H2("Confirmation failed"),
                new Paragraph("This link has expired, was already used, or the address is no longer free."
                    + " Request the change again from your profile."));
        }
    }
}
