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
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.religioustext.app.service.UserService;

/**
 * "Forgot password" — step 1: ask for the email and send a reset link.
 *
 * Reached from the LoginForm's built-in "Forgot password" button. To avoid
 * email enumeration, the page shows the SAME neutral confirmation whether or
 * not the address is registered; UserService.requestPasswordReset only emails a
 * link when an account actually exists.
 */
@Route("forgot")
@PageTitle("Reset Password — Common Root?")
@AnonymousAllowed
public class ForgotPasswordView extends VerticalLayout {

    private final UserService userService;

    public ForgotPasswordView(final UserService aUserService, final BuildInfo aBuildInfo) {
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

        final H2 title = new H2("Reset your password");
        title.getStyle().set("margin", "0 0 4px 0");

        final Paragraph note = new Paragraph(
            "Enter the email address for your account and we'll send you a link "
            + "to choose a new password.");
        note.getStyle()
            .set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("margin", "0 0 8px 0");

        final EmailField email = new EmailField("Email");
        email.setWidthFull();
        email.setRequired(true);
        email.setPlaceholder("your@email.com");

        final Span errorMsg = new Span();
        errorMsg.getStyle()
            .set("color", "var(--lumo-error-color)")
            .set("font-size", "13px")
            .set("display", "none");

        final Button submit = new Button("Send reset link");
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submit.setWidthFull();
        submit.addClickListener(e -> handleSubmit(card, email, errorMsg));
        email.addKeyPressListener(Key.ENTER, e -> handleSubmit(card, email, errorMsg));

        final Paragraph back = new Paragraph();
        back.add(new Anchor("login", "\u2190 Back to sign in"));
        back.getStyle().set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)");

        card.add(title, note, email, errorMsg, submit, back);
        add(card, aBuildInfo.pinned());
    }

    private void handleSubmit(final Div aCard, final EmailField anEmailField, final Span anErrorMsg) {
        clearError(anErrorMsg);
        final String emailVal = anEmailField.getValue() == null ? "" : anEmailField.getValue().trim();
        if (emailVal.isEmpty())      { error(anErrorMsg, "Email is required."); return; }
        if (!emailVal.contains("@")) { error(anErrorMsg, "Enter a valid email address."); return; }

        // Fire-and-forget: only sends if the account exists. Never reveals which.
        userService.requestPasswordReset(emailVal);

        // Replace the form with the same neutral confirmation in every case.
        aCard.removeAll();
        final H2 done = new H2("Check your inbox");
        done.getStyle().set("margin", "0 0 4px 0");
        final Paragraph msg = new Paragraph(
            "If that address has a Common Root? account, we've sent a link to "
            + "reset your password. The link is valid for about an hour.");
        msg.getStyle().set("font-size", "14px")
            .set("color", "var(--lumo-secondary-text-color)");
        final Paragraph back = new Paragraph();
        back.add(new Anchor("login", "\u2190 Back to sign in"));
        back.getStyle().set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)");
        aCard.add(done, msg, back);
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
