package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.service.ApiKeyService;
import org.religioustext.app.service.UserService;

/**
 * Profile / settings page. Requires authentication.
 * Lets the user update their display name, which appears in the reader
 * toolbar in place of the email prefix, and manage their API keys
 * ({@link ApiKeysSection}).
 *
 * The toolbar resolves the display name from the DB each page load (the
 * security principal stays email-only), so a save here shows up on the next
 * navigation back to the reader.
 */
@Route("profile")
@PageTitle("Profile — Common Root?")
@RolesAllowed("USER")
public class ProfileView extends VerticalLayout {

    private final UserService           userService;
    private final AuthenticationContext authContext;

    public ProfileView(final UserService aUserService,
                       final AuthenticationContext anAuthContext,
                       final ApiKeyService anApiKeyService,
                       final BuildInfo aBuildInfo) {
        this.userService = aUserService;
        this.authContext = anAuthContext;

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        // Top-aligned rather than centred: the keys grid makes the page taller than the viewport.
        getStyle().set("overflow", "auto");

        final String email = authContext.getPrincipalName().orElse("");
        final var currentUser = userService.findByEmail(email);
        final String currentDisplayName = currentUser
            .map(u -> u.getDisplayName() != null ? u.getDisplayName() : "")
            .orElse("");

        final H2 title = new H2("Profile");
        title.getStyle().set("margin-bottom", "4px");

        final Paragraph emailLine = new Paragraph("Signed in as " + email);
        emailLine.getStyle()
            .set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("margin", "0 0 16px 0");

        final TextField nameField = new TextField("Display name");
        nameField.setWidth("320px");
        nameField.setValue(currentDisplayName);
        nameField.setPlaceholder("Your name as shown to others");
        nameField.setHelperText("Leave blank to show your email address instead.");

        final Span errorMsg = new Span();
        errorMsg.getStyle()
            .set("color", "var(--lumo-error-color)")
            .set("font-size", "13px")
            .set("display", "none");

        final Button save = new Button("Save", e -> {
            errorMsg.getStyle().set("display", "none");
            try {
                userService.updateDisplayName(email, nameField.getValue());
                Notification.show("Display name updated. It will appear on your next page load.",
                    4000, Notification.Position.TOP_CENTER);
            } catch (final Exception ex) {
                errorMsg.setText("Save failed — please try again.");
                errorMsg.getStyle().set("display", "block");
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Button prefsBtn = new Button("Reader preferences",
            e -> getUI().ifPresent(ui -> ui.navigate("preferences")));
        prefsBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        // Admin-only: the shared/named ACL manager (mode-1 access control).
        final boolean isAdmin = currentUser.map(u -> u.getRole().atLeast(Role.admin)).orElse(false);
        final Button aclBtn = new Button("Access control",
            e -> getUI().ifPresent(ui -> ui.navigate("admin/acls")));
        aclBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        aclBtn.setVisible(isAdmin);

        final Button back = new Button("Back to reader",
            e -> getUI().ifPresent(ui -> ui.navigate("reader")));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        // API keys — any active account may hold keys (Consumer tier suffices, spec §2).
        final var keysSection = currentUser
            .map(u -> new ApiKeysSection(anApiKeyService, u.getId()))
            .orElse(null);

        add(title, emailLine, nameField, errorMsg, save);
        if (keysSection != null) add(keysSection);
        add(prefsBtn, aclBtn, back, aBuildInfo.pinned());
    }
}
