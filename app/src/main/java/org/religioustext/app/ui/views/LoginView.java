package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Sign-in page. Accessible without authentication.
 *
 * The Vaadin LoginForm posts username+password to the /login endpoint, which
 * Spring Security's UsernamePasswordAuthenticationFilter handles. On success,
 * Spring Security redirects to the original saved request or to "/". On
 * failure, the user is returned here with an ?error query parameter.
 *
 * The "username" field is labelled "Email" — the backend treats it as an email.
 *
 * Internal navigation (Forgot password, Create account) uses RouterLink with the
 * target view class, not Anchor with a relative href: the LoginForm's built-in
 * forgot button didn't reliably reach the server, and a relative Anchor href is
 * a soft spot under Vaadin's SPA base-href. RouterLink navigates by route and
 * always works for internal views. The "Back" link stays a plain Anchor since it
 * points at the application root.
 */
@Route("login")
@PageTitle("Sign In — Common Root?")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public LoginView(final BuildInfo aBuildInfo) {
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        final LoginI18n i18n = LoginI18n.createDefault();
        i18n.getForm().setUsername("Email");
        i18n.getForm().setTitle("Common Root?");
        i18n.getForm().setSubmit("Sign In");
        i18n.getErrorMessage().setTitle("Incorrect email or password");
        i18n.getErrorMessage().setMessage(
            "Check your email address and password and try again.");
        loginForm.setI18n(i18n);
        loginForm.setAction("login");  // Spring Security POST endpoint

        // Hide the built-in forgot button — its click doesn't reliably reach the
        // server. We provide our own RouterLink below instead.
        loginForm.setForgotPasswordButtonVisible(false);

        final RouterLink forgotLink = new RouterLink("Forgot password", ForgotPasswordView.class);
        final Paragraph forgot = new Paragraph(forgotLink);
        forgot.getStyle()
            .set("margin-top", "8px")
            .set("font-size", "14px");

        final Paragraph register = new Paragraph();
        register.add("Don't have an account? ");
        register.add(new RouterLink("Create one — it's free", RegisterView.class));
        register.getStyle()
            .set("margin-top", "4px")
            .set("font-size", "14px")
            .set("color", "var(--lumo-secondary-text-color)");

        final Paragraph back = new Paragraph();
        back.add(new Anchor("/", "\u2190 Back to Common Root?"));
        back.getStyle()
            .set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("margin-top", "4px");

        add(loginForm, forgot, register, back, aBuildInfo.pinned());
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent anEvent) {
        final var params = anEvent.getLocation().getQueryParameters().getParameters();
        if (params.containsKey("error")) {
            loginForm.setError(true);
        }
        if (params.containsKey("registered")) {
            Notification.show("Check your inbox — click the verification link to activate your account.",
                6000, Notification.Position.TOP_CENTER);
        }
        if (params.containsKey("verified")) {
            Notification.show("Email verified! You can now sign in.",
                4000, Notification.Position.TOP_CENTER);
        }
        if (params.containsKey("reset")) {
            Notification.show("Your password has been updated — you can now sign in.",
                4000, Notification.Position.TOP_CENTER);
        }
    }
}
