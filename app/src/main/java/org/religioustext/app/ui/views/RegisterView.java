// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.religioustext.app.service.UserService;

/**
 * Account creation page. Accessible without authentication.
 *
 * On success the user is redirected to /login?registered so a confirmation
 * notification is shown. Auto-login after registration is a future iteration.
 *
 * Reading and studying is always free — no account needed. An account only
 * adds the ability to leave private notes and comments on verses.
 */
@Route("register")
@PageTitle("Create Account — Common Root?")
@AnonymousAllowed
public class RegisterView extends VerticalLayout {

    private static final Logger log = LoggerFactory.getLogger(RegisterView.class);

    private final UserService userService;

    public RegisterView(final UserService aUserService, final BuildInfo aBuildInfo) {
        this.userService = aUserService;

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        final Div card = new Div();
        card.getStyle()
            .set("width", "100%")
            .set("max-width", "400px")
            .set("display", "flex")
            .set("flex-direction", "column")
            .set("gap", "8px");

        final H2 title = new H2("Create Account");
        title.getStyle().set("margin", "0 0 4px 0");

        final Paragraph note = new Paragraph(
            "Reading is always free — no account needed. "
            + "Create an account to annotate verses with private notes and comments.");
        note.getStyle()
            .set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("margin", "0 0 8px 0");

        final EmailField email = new EmailField("Email");
        email.setWidthFull();
        email.setRequired(true);
        email.setPlaceholder("your@email.com");

        final TextField displayName = new TextField("Display name (optional)");
        displayName.setWidthFull();
        displayName.setPlaceholder("How you'll appear to others");

        final PasswordField password = new PasswordField("Password");
        password.setWidthFull();
        password.setRequired(true);
        password.setHelperText("At least 8 characters");

        final PasswordField confirm = new PasswordField("Confirm password");
        confirm.setWidthFull();
        confirm.setRequired(true);

        final FormLayout form = new FormLayout(email, displayName, password, confirm);
        form.setWidthFull();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 1));

        final Span errorMsg = new Span();
        errorMsg.getStyle()
            .set("color", "var(--lumo-error-color)")
            .set("font-size", "13px")
            .set("display", "none");

        final Button submit = new Button("Create Account");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submit.setWidthFull();
        submit.addClickListener(e -> handleSubmit(
            email, displayName, password, confirm, errorMsg));

        // Allow submitting by pressing Enter in the confirm-password field
        confirm.addKeyPressListener(com.vaadin.flow.component.Key.ENTER,
            e -> handleSubmit(email, displayName, password, confirm, errorMsg));

        final Paragraph signIn = new Paragraph();
        signIn.add("Already have an account? ");
        signIn.add(new Anchor("login", "Sign in"));
        signIn.getStyle().set("font-size", "14px").set("margin-top", "4px");

        final Paragraph back = new Paragraph();
        back.add(new Anchor("/", "\u2190 Back to Common Root?"));
        back.getStyle().set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)");

        card.add(title, note, form, errorMsg, submit, signIn, back);
        add(card, aBuildInfo.pinned());
    }

    private void handleSubmit(final EmailField anEmailField,
                              final TextField aDisplayName,
                              final PasswordField aPassword,
                              final PasswordField aConfirm,
                              final Span anErrorMsg) {
        clearError(anErrorMsg);

        final String emailVal = anEmailField.getValue().trim();
        final String nameVal  = aDisplayName.getValue().trim();
        final String passVal  = aPassword.getValue();
        final String confVal  = aConfirm.getValue();

        if (emailVal.isEmpty())             { error(anErrorMsg, "Email is required."); return; }
        if (!emailVal.contains("@"))        { error(anErrorMsg, "Enter a valid email address."); return; }
        if (passVal.length() < 8)           { error(anErrorMsg, "Password must be at least 8 characters."); return; }
        if (!passVal.equals(confVal))       { error(anErrorMsg, "Passwords don't match."); return; }

        try {
            userService.register(emailVal, nameVal.isEmpty() ? null : nameVal, passVal);
            getUI().ifPresent(ui -> ui.navigate("login?registered"));
        } catch (final UserService.EmailAlreadyUsedException ex) {
            error(anErrorMsg, "That email address is already registered.");
        } catch (final Exception ex) {
            log.error("Registration failed for {}", emailVal, ex);
            error(anErrorMsg, "Something went wrong — please try again.");
        }
    }

    private void error(final Span anElement, final String aMessage) {
        anElement.setText(aMessage);
        anElement.getStyle().set("display", "block");
    }

    private void clearError(final Span anElement) {
        anElement.setText("");
        anElement.getStyle().set("display", "none");
    }
}
