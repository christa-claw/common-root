// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.spring.security.AuthenticationContext;
import org.religioustext.app.service.AccountDataService;
import org.religioustext.app.service.UserService;

import java.io.ByteArrayInputStream;

/**
 * Profile-page sections for the account's credentials: change password, change email. Both
 * re-ask for the current password, so a walk-up on an unlocked browser cannot take the account.
 * Also the way out: download everything held about the account, or delete it.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
class AccountSection extends VerticalLayout {

    private final UserService           userService;
    private final AccountDataService    accountData;
    private final AuthenticationContext authContext;
    private final String                email;

    AccountSection(final UserService aUserService, final AccountDataService anAccountData,
                   final AuthenticationContext anAuthContext, final String anEmail) {
        this.userService = aUserService;
        this.accountData = anAccountData;
        this.authContext = anAuthContext;
        this.email       = anEmail;
        setPadding(false);
        setAlignItems(Alignment.CENTER);
        add(passwordForm(), emailForm(), dataSection());
    }

    private VerticalLayout dataSection() {
        final Anchor download = downloadLink();
        final Paragraph note = new Paragraph("Your notes, saved searches, comments, preferences and "
            + "key labels in one file.");
        note.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)").set("margin", "0");

        final Button delete = new Button("Delete my account\u2026", e -> confirmDelete());
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        return section("Your data", download, note, delete);
    }

    /** The one place the export link is built, so the profile page and the delete dialog offer the same file. */
    private Anchor downloadLink() {
        final Anchor link = new Anchor(
            new StreamResource("common-root-my-data.json",
                () -> new ByteArrayInputStream(accountData.exportJson(email))),
            "Download my data (JSON)");
        link.getElement().setAttribute("download", true);
        return link;
    }

    /** Ask for the password, then delete for good and sign out. */
    private void confirmDelete() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Delete your account?");
        dialog.setWidth("420px");
        final Paragraph warn = new Paragraph("This permanently deletes your account and everything "
            + "in it: your notes, saved searches, preferences and API keys, and every comment you "
            + "wrote \u2014 public ones too. It cannot be undone.");
        final Paragraph keepCopy = new Paragraph("Want a copy first? Download it here \u2014 it is optional:");
        keepCopy.getStyle().set("margin-bottom", "0");
        final PasswordField password = field("Your password");
        password.setWidthFull();
        final Button cancel = new Button("Cancel", e -> dialog.close());
        final Button confirm = new Button("Delete forever (comments, notes, keys)", e -> {
            password.setInvalid(false);
            try {
                accountData.deleteAccount(email, password.getValue());
                dialog.close();
                authContext.logout();
            } catch (final UserService.WrongPasswordException ex) {
                password.setErrorMessage("Incorrect password");
                password.setInvalid(true);
            } catch (final AccountDataService.AccountProtectedException ex) {
                password.setErrorMessage("Admin and system accounts cannot be deleted here");
                password.setInvalid(true);
            }
        });
        confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        dialog.add(warn, keepCopy, downloadLink(), password);
        dialog.getFooter().add(cancel, confirm);
        dialog.open();
    }

    private VerticalLayout passwordForm() {
        final PasswordField current = field("Current password");
        final PasswordField next    = field("New password");
        next.setHelperText("At least 8 characters.");
        final PasswordField confirm = field("Confirm new password");

        final Button save = new Button("Change password", e -> {
            current.setInvalid(false); next.setInvalid(false); confirm.setInvalid(false);
            if (!next.getValue().equals(confirm.getValue())) {
                confirm.setErrorMessage("Passwords do not match");
                confirm.setInvalid(true);
                return;
            }
            try {
                userService.changePassword(email, current.getValue(), next.getValue());
                current.clear(); next.clear(); confirm.clear();
                Notification.show("Password changed.", 4000, Notification.Position.TOP_CENTER);
            } catch (final UserService.WrongPasswordException ex) {
                current.setErrorMessage("Incorrect password");
                current.setInvalid(true);
            } catch (final UserService.WeakPasswordException ex) {
                next.setErrorMessage("Password must be at least 8 characters");
                next.setInvalid(true);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        return section("Change password", current, next, confirm, save);
    }

    private VerticalLayout emailForm() {
        final EmailField newEmail = new EmailField("New email address");
        newEmail.setWidth("320px");
        final PasswordField current = field("Current password");

        final Button save = new Button("Send confirmation link", e -> {
            newEmail.setInvalid(false); current.setInvalid(false);
            try {
                userService.requestEmailChange(email, current.getValue(), newEmail.getValue());
                current.clear(); newEmail.clear();
                Notification.show("Check the new address for a confirmation link. "
                    + "Your sign-in address changes once you click it.", 8000, Notification.Position.TOP_CENTER);
            } catch (final UserService.WrongPasswordException ex) {
                current.setErrorMessage("Incorrect password");
                current.setInvalid(true);
            } catch (final UserService.InvalidEmailException ex) {
                newEmail.setErrorMessage("Enter a valid email address");
                newEmail.setInvalid(true);
            } catch (final UserService.EmailAlreadyUsedException ex) {
                newEmail.setErrorMessage("That address is already in use");
                newEmail.setInvalid(true);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        final Paragraph note = new Paragraph("We email a link to the new address. Nothing changes until you click it.");
        note.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)").set("margin", "0");
        return section("Change email address", newEmail, current, note, save);
    }

    private static PasswordField field(final String aLabel) {
        final PasswordField f = new PasswordField(aLabel);
        f.setWidth("320px");
        return f;
    }

    private static VerticalLayout section(final String aTitle,
                                          final com.vaadin.flow.component.Component... theParts) {
        final VerticalLayout box = new VerticalLayout();
        box.setPadding(false);
        box.setAlignItems(Alignment.CENTER);
        box.add(new H3(aTitle));
        box.add(theParts);
        return box;
    }
}
