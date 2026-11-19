// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.UserRepository;
import org.religioustext.app.service.AppSignInService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /app/sign-in} — where the phone app sends a reader to sign in
 * ({@link AppSignInService}). A signed-out reader meets the site's own login page first and
 * comes back here after it.
 *
 * <p>The page asks once before handing the account to the app, so a link that opened it
 * without the reader meaning to cannot sign an app in behind their back. Continue and Cancel
 * are plain links to the app's address: a link the reader taps is a navigation the phone's
 * browser will hand to an app, where a script-driven redirect may be blocked.
 *
 * <p>The code is minted as the page opens, not on the tap. It is useless without the app's
 * secret, used once, and gone after {@code AppSignInService.CODE_LIFETIME}.
 *
 * @author Christa Claw
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Route("app/sign-in")
@PermitAll
public class AppSignInView extends VerticalLayout implements BeforeEnterObserver, HasDynamicTitle {

    private static final String NAVY = "#1a3a5c";
    private static final String GOLD = "#c9a84c";

    private final AppSignInService      signIn;
    private final UserRepository        users;
    private final AuthenticationContext auth;
    private final Div                   panel = new Div();

    /**
     * @param aBuildInfo            the build identity, for the shared header
     * @param anAppSignInService    mints the one-time code
     * @param aUserRepository       finds the signed-in account
     * @param anAuthContext         who is signed in
     */
    public AppSignInView(final BuildInfo aBuildInfo, final AppSignInService anAppSignInService,
                         final UserRepository aUserRepository, final AuthenticationContext anAuthContext) {
        this.signIn = anAppSignInService;
        this.users  = aUserRepository;
        this.auth   = anAuthContext;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("overflow-y", "auto");
        panel.getStyle()
            .set("max-width", "560px").set("margin", "40px auto").set("padding", "0 16px")
            .set("box-sizing", "border-box").set("width", "100%");
        add(new SiteHeader(aBuildInfo), panel);
    }

    /** {@inheritDoc} */
    @Override
    public String getPageTitle() {
        return t("appSignIn.title");
    }

    /**
     * Check the app's parameters and show either the confirmation or why it cannot go on.
     *
     * @param anEvent the navigation, for the query string
     */
    @Override
    public void beforeEnter(final BeforeEnterEvent anEvent) {
        panel.removeAll();
        final H1 title = new H1(t("appSignIn.title"));
        title.getStyle().set("color", NAVY).set("font-size", "28px").set("margin", "0 0 16px 0");
        panel.add(title);

        final Map<String, List<String>> q = anEvent.getLocation().getQueryParameters().getParameters();
        final String challenge = one(q, "code_challenge");
        final String method    = one(q, "code_challenge_method");
        final String state     = one(q, "state");
        final String redirect  = one(q, "redirect_uri");
        final Optional<User> user = auth.getPrincipalName().flatMap(users::findByEmailIgnoreCase);
        if (user.isEmpty() || !"S256".equals(method) || state == null || state.length() > 200
                || !AppSignInService.isChallenge(challenge) || !signIn.isAllowedRedirect(redirect)) {
            panel.add(text(t("appSignIn.invalid")));
            return;
        }

        final String code = signIn.issueCode(user.get().getId(), challenge, redirect);
        final String name = user.get().getDisplayName() == null || user.get().getDisplayName().isBlank()
            ? user.get().getEmail() : user.get().getDisplayName();
        panel.add(text(t("appSignIn.lead", name)), text(t("appSignIn.keyNote")));

        final Anchor go = new Anchor(back(redirect, "code", code, state), t("appSignIn.continue"));
        go.getStyle()
            .set("display", "inline-block").set("background", GOLD).set("color", NAVY)
            .set("font-weight", "700").set("font-size", "17px").set("padding", "12px 28px")
            .set("border-radius", "6px").set("text-decoration", "none").set("margin-right", "16px");
        go.getElement().setAttribute("router-ignore", true);
        final Anchor cancel = new Anchor(back(redirect, "error", "access_denied", state),
            t("appSignIn.cancel"));
        cancel.getElement().setAttribute("router-ignore", true);
        cancel.getStyle().set("color", "var(--lumo-secondary-text-color)");
        final Div actions = new Div(go, cancel);
        actions.getStyle().set("margin-top", "24px").set("display", "flex").set("align-items", "center")
            .set("flex-wrap", "wrap").set("row-gap", "12px");
        panel.add(actions);
    }

    /**
     * The app's address with the answer and the app's own {@code state} added.
     *
     * @param aRedirect the app's checked address
     * @param aName     {@code code}, or {@code error} when the reader cancelled
     * @param aValue    the code, or the error slug
     * @param aState    the app's {@code state}, returned unchanged
     * @return e.g. {@code commonroot://auth?code=…&state=…}
     */
    static String back(final String aRedirect, final String aName, final String aValue, final String aState) {
        return aRedirect + "?" + aName + "=" + URLEncoder.encode(aValue, StandardCharsets.UTF_8)
            + "&state=" + URLEncoder.encode(aState, StandardCharsets.UTF_8);
    }

    private static String one(final Map<String, List<String>> theParams, final String aName) {
        final List<String> v = theParams.get(aName);
        return v == null || v.size() != 1 ? null : v.get(0);
    }

    private static Paragraph text(final String aText) {
        final Paragraph p = new Paragraph(aText);
        p.getStyle().set("font-size", "16px").set("line-height", "1.6").set("margin", "0 0 12px 0");
        return p;
    }

    private String t(final String aKey, final Object... theParams) {
        return getTranslation(aKey, LocaleUtil.currentLocale(), theParams);
    }
}
