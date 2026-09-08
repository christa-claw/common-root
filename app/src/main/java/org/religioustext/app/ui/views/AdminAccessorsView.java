package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.service.AccessorAdminService;
import org.religioustext.app.service.AccessorAdminService.GroupRow;
import org.religioustext.app.service.AccessorAdminService.MemberOption;
import org.religioustext.app.service.AccessorAdminService.MemberRow;
import org.religioustext.app.service.AccessorAdminService.UserRow;

import java.util.List;
import java.util.Locale;

/**
 * Admin editor for ACCESSORS — the principals themselves (docs/access-control.md §1, §4): invite
 * users, move them on the role ladder, create groups (title + description), and edit group
 * membership. The counterpart to {@link AdminAclView}, which edits what accessors are GRANTED.
 * Master-detail like its sibling: users and groups on the left, the selected accessor edited on
 * the right. Admin + superuser only; the ladder itself is enforced in
 * {@link AccessorAdminService}, so the view stays honest no matter what it renders.
 *
 * @author Christa Claw
 * @version 0.6.3-SNAPSHOT
 * @since 0.6.3
 */
@Route("admin/accessors")
@PageTitle("Users & groups — Common Root?")
@RolesAllowed("ADMIN")
public class AdminAccessorsView extends VerticalLayout {

    private final AccessorAdminService accessors;
    private final String email;

    private final Div list   = new Div();   // left: users + groups
    private final Div editor = new Div();   // right: selected accessor's editor
    private final TextField filter = new TextField();
    private final Checkbox showChannels = new Checkbox("Show channel accounts");
    private String selectedId;              // usr-… or grp-… (prefix discriminates)

    public AdminAccessorsView(final AccessorAdminService anAccessorAdminService
                              , final AuthenticationContext anAuthContext
                              , final BuildInfo aBuildInfo) {
        this.accessors = anAccessorAdminService;
        this.email = anAuthContext.getPrincipalName().orElse("");

        setSizeFull();
        getStyle().set("overflow-y", "auto").set("padding", "28px 32px");

        final H2 title = new H2("Users & groups");
        title.getStyle().set("font-size", "26px").set("font-weight", "700").set("color", "#1a3a5c")
            .set("margin", "0 0 4px").set("border-bottom", "3px solid #c9a84c")
            .set("padding-bottom", "8px").set("display", "inline-block");

        final Paragraph intro = new Paragraph(
            "Invite accounts, set their role on the ladder, and manage the groups access is "
            + "granted through. What an accessor may DO with an object is edited on the ACLs "
            + "page — this page manages who the accessors are.");
        intro.getStyle().set("font-size", "14px").set("color", "#555")
            .set("max-width", "620px").set("line-height", "1.7").set("margin", "12px 0 20px");

        list.getStyle().set("width", "280px").set("flex-shrink", "0")
            .set("border-right", "1px solid var(--lumo-contrast-10pct)").set("padding-right", "14px")
            .set("display", "flex").set("flex-direction", "column").set("gap", "6px");
        editor.getStyle().set("flex", "1").set("padding-left", "20px").set("min-width", "0");

        final HorizontalLayout body = new HorizontalLayout(list, editor);
        body.setWidthFull();
        body.setAlignItems(Alignment.START);

        final Anchor acls = new Anchor("admin/acls", "Access control (ACLs) →");
        acls.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)")
            .set("margin-top", "18px");
        final Anchor back = new Anchor("profile", "← Back to profile");
        back.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)");

        filter.setPlaceholder("Filter…");
        filter.setClearButtonVisible(true);
        filter.setWidthFull();
        filter.addValueChangeListener(e -> refreshList());
        showChannels.setValue(false);
        showChannels.getStyle().set("font-size", "12px");
        showChannels.addValueChangeListener(e -> refreshList());

        add(title, intro, body, acls, back, aBuildInfo.pinned());
        refreshList();
        renderEditor();
    }

    // ---- left column --------------------------------------------------------

    private void refreshList() {
        list.removeAll();
        list.add(filter);

        final List<UserRow> userRows;
        final List<GroupRow> groupRows;
        try {
            userRows = accessors.users(email);
            groupRows = accessors.groups(email);
        } catch (final RuntimeException ex) {
            list.add(new Span(ex.getMessage()));
            return;
        }

        final String needle = filter.getValue() == null
            ? "" : filter.getValue().trim().toLowerCase(Locale.ROOT);

        list.add(sectionHeading("Users"));
        list.add(showChannels);
        for (final UserRow u : userRows) {
            if (u.system() && !showChannels.getValue()) continue;
            final String hay = (u.email() + " " + (u.displayName() == null ? "" : u.displayName()))
                .toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !hay.contains(needle)) continue;
            list.add(userListItem(u));
        }
        final Button invite = new Button("Invite user", e -> openInviteDialog());
        invite.setIcon(VaadinIcon.PLUS.create());
        invite.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        list.add(invite);

        list.add(sectionHeading("Groups"));
        for (final GroupRow g : groupRows) {
            final String hay = (g.name() + " " + (g.label() == null ? "" : g.label()))
                .toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && !hay.contains(needle)) continue;
            list.add(groupListItem(g));
        }
        final Button newGroup = new Button("New group", e -> openNewGroupDialog());
        newGroup.setIcon(VaadinIcon.PLUS.create());
        newGroup.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        list.add(newGroup);
    }

    private Span sectionHeading(final String aText) {
        final Span heading = new Span(aText);
        heading.getStyle().set("font-size", "12px").set("font-weight", "700").set("color", "#888")
            .set("text-transform", "uppercase").set("letter-spacing", "0.04em")
            .set("margin", "10px 0 4px");
        return heading;
    }

    private Div userListItem(final UserRow aUser) {
        final String name = aUser.displayName() != null && !aUser.displayName().isBlank()
            ? aUser.displayName() : aUser.email();
        final String sub = aUser.system() ? "channel · " + aUser.role()
            : aUser.role() + (aUser.verified() ? "" : " · invited") + (aUser.active() ? "" : " · inactive");
        return listItem(aUser.id(), name, sub);
    }

    private Div groupListItem(final GroupRow aGroup) {
        final String sub = (aGroup.system() ? "system" : "group")
            + " · " + aGroup.memberCount() + (aGroup.memberCount() == 1 ? " member" : " members");
        return listItem(aGroup.id(), aGroup.label() != null ? aGroup.label() : aGroup.name(), sub);
    }

    private Div listItem(final String anId, final String aName, final String aSub) {
        final Div item = new Div();
        final boolean selected = anId.equals(selectedId);
        item.getStyle().set("cursor", "pointer").set("border-radius", "6px").set("padding", "8px 10px")
            .set("background", selected ? "#1a3a5c" : "#f8fafc")
            .set("border", selected ? "1px solid #1a3a5c" : "1px solid var(--lumo-contrast-10pct)");

        final Span name = new Span(aName);
        name.getStyle().set("display", "block").set("font-size", "14px")
            .set("color", selected ? "white" : "#1a3a5c")
            .set("overflow", "hidden").set("text-overflow", "ellipsis").set("white-space", "nowrap");

        final Span sub = new Span(aSub);
        sub.getStyle().set("display", "block").set("font-size", "11px")
            .set("color", selected ? "#c9d4e0" : "#888");

        item.add(name, sub);
        item.addClickListener(e -> { selectedId = anId; refreshList(); renderEditor(); });
        return item;
    }

    // ---- right column -------------------------------------------------------

    private void renderEditor() {
        editor.removeAll();
        if (selectedId == null) {
            final Span hint = new Span("Select a user or group, or create a new one.");
            hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "14px");
            editor.add(hint);
            return;
        }
        try {
            if (selectedId.startsWith("usr-")) renderUserEditor();
            else                               renderGroupEditor();
        } catch (final RuntimeException ex) {
            editor.add(new Span(ex.getMessage()));
        }
    }

    private void renderUserEditor() {
        final UserRow user = accessors.users(email).stream()
            .filter(u -> u.id().equals(selectedId)).findFirst().orElse(null);
        if (user == null) { editor.add(new Span("User not found.")); return; }

        editor.add(editorTitle(user.displayName() != null && !user.displayName().isBlank()
            ? user.displayName() : user.email()));

        final Span meta = new Span(user.email()
            + (user.system() ? " · channel account" : "")
            + (user.verified() ? "" : " · invite not yet claimed")
            + (user.active() ? "" : " · inactive"));
        meta.getStyle().set("display", "block").set("font-size", "13px").set("color", "#888")
            .set("margin", "0 0 14px");
        editor.add(meta);

        // Role, ladder-limited: rungs this actor may not grant are shown but not offered.
        final List<Role> grantable = accessors.grantableRoles(email);
        final Select<Role> role = new Select<>();
        role.setLabel("Role");
        role.setItems(grantable.contains(user.role()) ? grantable : List.of(user.role()));
        role.setValue(user.role());
        role.setWidth("200px");
        role.setReadOnly(!grantable.contains(user.role()));
        if (role.isReadOnly())
            role.setHelperText("Only a superuser may change this rung");
        role.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            try {
                accessors.setRole(email, selectedId, e.getValue());
                Notification.show("Role updated");
                refreshList();
            } catch (final RuntimeException ex) {
                Notification.show(ex.getMessage());
                role.setValue(e.getOldValue());
            }
        });
        editor.add(role);

        if (!user.verified() && !user.system()) {
            final Button resend = new Button("Resend invite", e -> {
                try {
                    accessors.resendInvite(email, selectedId);
                    Notification.show("Invite re-sent");
                } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
            });
            resend.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            resend.getStyle().set("display", "block").set("margin-top", "12px");
            editor.add(resend);
        }
    }

    private void renderGroupEditor() {
        final GroupRow group = accessors.groups(email).stream()
            .filter(g -> g.id().equals(selectedId)).findFirst().orElse(null);
        if (group == null) { editor.add(new Span("Group not found.")); return; }

        editor.add(editorTitle(group.label() != null ? group.label() : group.name()));

        final Span meta = new Span(group.name() + (group.system() ? " · system group" : ""));
        meta.getStyle().set("display", "block").set("font-size", "13px").set("color", "#888")
            .set("margin", "0 0 6px");
        editor.add(meta);

        if (group.description() != null) {
            final Paragraph desc = new Paragraph(group.description());
            desc.getStyle().set("font-size", "14px").set("color", "#555")
                .set("max-width", "560px").set("line-height", "1.6").set("margin", "0 0 14px");
            editor.add(desc);
        }

        editor.add(sectionHeading("Members"));
        final List<MemberRow> members = accessors.members(email, selectedId);
        if (members.isEmpty()) {
            final Span none = new Span("No members yet.");
            none.getStyle().set("font-size", "13px").set("color", "#888");
            editor.add(none);
        }
        for (final MemberRow m : members) editor.add(memberRow(m));
        editor.add(addMemberRow());

        if (!group.system()) {
            final Button delete = new Button("Delete group", e -> confirmDeleteGroup(group));
            delete.setIcon(VaadinIcon.TRASH.create());
            delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
            delete.getStyle().set("margin-top", "20px");
            editor.add(delete);
        }
    }

    private H2 editorTitle(final String aText) {
        final H2 t = new H2(aText);
        t.getStyle().set("font-size", "18px").set("font-weight", "700").set("color", "#1a3a5c")
            .set("margin", "0 0 2px");
        return t;
    }

    private Div memberRow(final MemberRow aMember) {
        final Div r = new Div();
        r.getStyle().set("display", "flex").set("align-items", "center")
            .set("justify-content", "space-between").set("gap", "10px").set("padding", "6px 0")
            .set("border-bottom", "0.5px solid var(--lumo-contrast-5pct)").set("max-width", "560px");

        final Span who = new Span(aMember.label()
            + (aMember.kind() == Ace.AccessorKind.group ? " (group)" : ""));
        who.getStyle().set("font-size", "14px");

        final Button remove = new Button(VaadinIcon.TRASH.create(), e -> {
            try {
                accessors.removeMember(email, selectedId, aMember.accessorId());
                Notification.show("Member removed");
                refreshList();
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        remove.setAriaLabel("Remove");
        remove.setTooltipText("Remove");

        r.add(who, remove);
        return r;
    }

    /** The searchable add-member row (excludes the group itself and existing members). */
    private Div addMemberRow() {
        final Div r = new Div();
        r.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px")
            .set("margin-top", "12px").set("padding-top", "12px").set("max-width", "560px")
            .set("border-top", "0.5px solid var(--lumo-contrast-10pct)");

        final ComboBox<MemberOption> picker = new ComboBox<>();
        picker.setItems(accessors.memberOptions(email, selectedId));
        picker.setItemLabelGenerator(MemberOption::label);
        picker.setPlaceholder("Add a user or group…");
        picker.getStyle().set("flex", "1");

        final Button add = new Button("Add", e -> {
            final MemberOption sel = picker.getValue();
            if (sel == null) return;
            try {
                accessors.addMember(email, selectedId, sel);
                Notification.show("Member added");
                refreshList();
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        add.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        r.add(picker, add);
        return r;
    }

    // ---- dialogs ------------------------------------------------------------

    private void openInviteDialog() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Invite user");
        dialog.setWidth("400px");

        final TextField inviteeEmail = new TextField("Email");
        inviteeEmail.setWidthFull();
        inviteeEmail.setPlaceholder("person@example.org");

        final TextField displayName = new TextField("Display name (optional)");
        displayName.setWidthFull();

        final Select<Role> role = new Select<>();
        role.setLabel("Role");
        role.setItems(accessors.grantableRoles(email));
        role.setValue(Role.consumer);
        role.setWidthFull();

        final Span note = new Span("The invitee gets an email with a link to choose a password "
            + "(valid 7 days). The account is unusable until they claim it.");
        note.getStyle().set("font-size", "12px").set("color", "#888").set("line-height", "1.5");

        final VerticalLayout form = new VerticalLayout(inviteeEmail, displayName, role, note);
        form.setPadding(false);
        dialog.add(form);

        final Button create = new Button("Invite", e -> {
            if (inviteeEmail.getValue() == null || inviteeEmail.getValue().isBlank()) {
                Notification.show("Email is required");
                return;
            }
            try {
                selectedId = accessors.invite(email, inviteeEmail.getValue(),
                    displayName.getValue(), role.getValue());
                Notification.show("Invite sent");
                dialog.close();
                refreshList();
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), create);
        dialog.open();
    }

    private void openNewGroupDialog() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New group");
        dialog.setWidth("400px");

        final TextField name = new TextField("Name");
        name.setWidthFull();
        name.setPlaceholder("e.g. moderators");
        name.setHelperText("Unique internal name; channel-members: is reserved");

        final TextField label = new TextField("Title (optional)");
        label.setWidthFull();
        label.setPlaceholder("e.g. Comment moderators");

        final TextArea description = new TextArea("Description (optional)");
        description.setWidthFull();
        description.setPlaceholder("What is this group for?");
        description.setMaxLength(500);

        final VerticalLayout form = new VerticalLayout(name, label, description);
        form.setPadding(false);
        dialog.add(form);

        final Button create = new Button("Create", e -> {
            try {
                selectedId = accessors.createGroup(email, name.getValue(),
                    label.getValue(), description.getValue());
                Notification.show("Group created");
                dialog.close();
                refreshList();
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), create);
        dialog.open();
    }

    private void confirmDeleteGroup(final GroupRow aGroup) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Delete group");
        dialog.setWidth("400px");
        dialog.add(new Paragraph("Delete \"" + (aGroup.label() != null ? aGroup.label() : aGroup.name())
            + "\" and its " + aGroup.memberCount() + " membership row"
            + (aGroup.memberCount() == 1 ? "" : "s")
            + "? ACL entries naming the group are not touched."));

        final Button delete = new Button("Delete", e -> {
            try {
                accessors.deleteGroup(email, aGroup.id());
                Notification.show("Group deleted");
                dialog.close();
                selectedId = null;
                refreshList();
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        delete.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), delete);
        dialog.open();
    }
}
