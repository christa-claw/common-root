package org.religioustext.app.ui.views;

import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.religioustext.app.service.UserService;

/**
 * "Forgot password" — step 2: the reset link target, /reset?token=XYZ.
 *
 * Captures the token from the URL, collects a new password, and asks
 * UserService to complete the reset (which validates the token and its expiry).
 * On success, redirects to /login?reset so LoginView confirms. A missing token
 * or a failed reset shows a clear message with a path back to /forgot.
 */
@Route("reset")
@PageTitle("Set New Password — Common Root?")
@AnonymousAllowed
public class ResetPasswordView extends VerticalLayout implements BeforeEnterObserver {

    private final UserService userService;
    private String token;

    private final Div          card     = new Div();
    private final PasswordField password = new PasswordField("New password");
    private final PasswordField confirm  = new PasswordField("Confirm new password");
    private final Span          errorMsg = new Span();

    public ResetPasswordView(final UserService aUserService, final BuildInfo aBuildInfo) {
        this.userService = aUserService;

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        card.getStyle()
            .set("width", "100%")
            .set("max-width", "400px")
            .set("display", "flex")
            .set("flex-direction", "column")
            .set("gap", "8px");
        add(card, aBuildInfo.pinned());
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent anEvent) {
        final var tokens = anEvent.getLocation().getQueryParameters().getParameters().get("token");
        if (tokens == null || tokens.isEmpty() || tokens.get(0).isBlank()) {
            showInvalidLink();
            return;
        }
        this.token = tokens.get(0);
        showForm();
    }

    private void showForm() {
        card.removeAll();

        final H2 title = new H2("Choose a new password");
        title.getStyle().set("margin", "0 0 4px 0");

        password.setWidthFull();
        password.setRequired(true);
        password.setHelperText("At least 8 characters");
        confirm.setWidthFull();
        confirm.setRequired(true);

        errorMsg.setText("");
        errorMsg.getStyle()
            .set("color", "var(--lumo-error-color)")
            .set("font-size", "13px")
            .set("display", "none");

        final Button submit = new Button("Set new password");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submit.setWidthFull();
        submit.addClickListener(e -> handleSubmit());
        confirm.addKeyPressListener(Key.ENTER, e -> handleSubmit());

        final Paragraph back = new Paragraph();
        back.add(new Anchor("login", "\u2190 Back to sign in"));
        back.getStyle().set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)");

        card.add(title, password, confirm, errorMsg, submit, back);
    }

    private void handleSubmit() {
        clearError();
        final String pass = password.getValue();
        final String conf = confirm.getValue();

        if (pass == null || pass.length() < 8) { error("Password must be at least 8 characters."); return; }
        if (!pass.equals(conf))                 { error("Passwords don't match."); return; }

        final boolean ok;
        try {
            ok = userService.resetPassword(token, pass);
        } catch (final UserService.WeakPasswordException ex) {
            error("Password must be at least 8 characters.");
            return;
        } catch (final Exception ex) {
            error("Something went wrong — please try again.");
            return;
        }

        if (ok) {
            getUI().ifPresent(ui -> ui.navigate("login?reset"));
        } else {
            showExpiredLink();
        }
    }

    private void showInvalidLink() {
        card.removeAll();
        final H2 title = new H2("Invalid reset link");
        title.getStyle().set("margin", "0 0 4px 0");
        final Paragraph msg = new Paragraph(
            "This password-reset link is missing or malformed. Please request a new one.");
        msg.getStyle().set("font-size", "14px").set("color", "var(--lumo-secondary-text-color)");
        card.add(title, msg, requestAgainLink());
    }

    private void showExpiredLink() {
        card.removeAll();
        final H2 title = new H2("Link expired or already used");
        title.getStyle().set("margin", "0 0 4px 0");
        final Paragraph msg = new Paragraph(
            "This reset link is no longer valid. Reset links expire after about "
            + "an hour and can only be used once. Please request a new one.");
        msg.getStyle().set("font-size", "14px").set("color", "var(--lumo-secondary-text-color)");
        card.add(title, msg, requestAgainLink());
    }

    private Paragraph requestAgainLink() {
        final Paragraph p = new Paragraph();
        p.add(new Anchor("forgot", "Request a new reset link"));
        p.getStyle().set("font-size", "14px");
        return p;
    }

    private void error(final String aMessage) {
        errorMsg.setText(aMessage);
        errorMsg.getStyle().set("display", "block");
    }

    private void clearError() {
        errorMsg.setText("");
        errorMsg.getStyle().set("display", "none");
    }
}
