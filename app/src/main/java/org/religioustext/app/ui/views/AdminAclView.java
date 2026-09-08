package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import org.religioustext.app.service.NamedAclService;
import org.religioustext.app.service.NamedAclService.AccessorOption;
import org.religioustext.app.service.NamedAclService.AceRow;
import org.religioustext.app.service.NamedAclService.AclSummary;
import org.religioustext.app.service.NamedAclService.NamedAclView;
import org.religioustext.app.service.NamedAclService.OrgOption;

import java.util.List;

/**
 * Admin editor for MODE-1 named/shared ACLs (docs/access-control.md §4a) — the direct-editing
 * counterpart to the per-comment dialog. A master-detail page: the named ACLs on the left (system
 * default + each org default + any hand-crafted), the selected ACL's ACEs edited on the right.
 * Changes here affect EVERY object attached to the ACL. Admin + superuser only.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Route("admin/acls")
@RouteAlias("admin")   // /admin lands here; its sibling is /admin/accessors (users & groups)
@PageTitle("Access control — Common Root?")
@RolesAllowed("ADMIN")
public class AdminAclView extends VerticalLayout {

    private static final List<String> LEVELS = List.of("none", "browse", "read", "write", "delete");
    /** Owner floored at read (retention): the owner row omits none/browse. */
    private static final List<String> OWNER_LEVELS = List.of("read", "write", "delete");

    private final NamedAclService namedAcls;
    private final String email;

    private final Div list   = new Div();   // left: ACL list + New ACL
    private final Div editor = new Div();   // right: selected ACL's editor
    private String selectedId;

    public AdminAclView(final NamedAclService aNamedAclService,
                        final AuthenticationContext anAuthContext,
                        final BuildInfo aBuildInfo) {
        this.namedAcls = aNamedAclService;
        this.email = anAuthContext.getPrincipalName().orElse("");

        setSizeFull();
        getStyle().set("overflow-y", "auto").set("padding", "28px 32px");

        final H2 title = new H2("Access control");
        title.getStyle().set("font-size", "26px").set("font-weight", "700").set("color", "#1a3a5c")
            .set("margin", "0 0 4px").set("border-bottom", "3px solid #c9a84c")
            .set("padding-bottom", "8px").set("display", "inline-block");

        final Paragraph intro = new Paragraph(
            "Edit the shared ACLs that govern comments. Changes here apply to every object "
            + "attached to the ACL. Per-comment overrides are edited from the comment itself.");
        intro.getStyle().set("font-size", "14px").set("color", "#555")
            .set("max-width", "620px").set("line-height", "1.7").set("margin", "12px 0 20px");

        list.getStyle().set("width", "240px").set("flex-shrink", "0")
            .set("border-right", "1px solid var(--lumo-contrast-10pct)").set("padding-right", "14px")
            .set("display", "flex").set("flex-direction", "column").set("gap", "6px");
        editor.getStyle().set("flex", "1").set("padding-left", "20px").set("min-width", "0");

        final HorizontalLayout body = new HorizontalLayout(list, editor);
        body.setWidthFull();
        body.setAlignItems(Alignment.START);

        final Anchor accessors = new Anchor("admin/accessors", "Users & groups →");
        accessors.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)")
            .set("margin-top", "18px");
        final Anchor back = new Anchor("profile", "← Back to profile");
        back.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)");

        add(title, intro, body, accessors, back, aBuildInfo.pinned());
        refreshList();
        renderEditor();
    }

    private void refreshList() {
        list.removeAll();
        final Span heading = new Span("ACLs");
        heading.getStyle().set("font-size", "12px").set("font-weight", "700").set("color", "#888")
            .set("text-transform", "uppercase").set("letter-spacing", "0.04em").set("margin-bottom", "4px");
        list.add(heading);

        final List<AclSummary> acls;
        try {
            acls = namedAcls.list(email);
        } catch (final RuntimeException ex) {
            list.add(new Span(ex.getMessage()));
            return;
        }
        for (final AclSummary s : acls) {
            list.add(aclListItem(s));
        }

        final Button newAcl = new Button("New ACL", e -> openNewDialog());
        newAcl.setIcon(VaadinIcon.PLUS.create());
        newAcl.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        newAcl.getStyle().set("margin-top", "8px");
        list.add(newAcl);
    }

    private Div aclListItem(final AclSummary aSummary) {
        final Div item = new Div();
        final boolean selected = aSummary.id().equals(selectedId);
        item.getStyle().set("cursor", "pointer").set("border-radius", "6px").set("padding", "8px 10px")
            .set("background", selected ? "#1a3a5c" : "#f8fafc")
            .set("border", selected ? "1px solid #1a3a5c" : "1px solid var(--lumo-contrast-10pct)");

        final Span name = new Span(aSummary.label() != null && !aSummary.label().isBlank()
            ? aSummary.label() : aSummary.name());
        name.getStyle().set("display", "block").set("font-size", "14px")
            .set("color", selected ? "white" : "#1a3a5c");

        final Span sub = new Span(aSummary.system() ? "system"
            : (aSummary.attachedOrg() != null ? "org · " + aSummary.attachedOrg() : "unattached"));
        sub.getStyle().set("display", "block").set("font-size", "11px")
            .set("color", selected ? "#c9d4e0" : "#888");

        item.add(name, sub);
        item.addClickListener(e -> { selectedId = aSummary.id(); refreshList(); renderEditor(); });
        return item;
    }

    private void renderEditor() {
        editor.removeAll();
        if (selectedId == null) {
            final Span hint = new Span("Select an ACL to edit, or create a new one.");
            hint.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "14px");
            editor.add(hint);
            return;
        }
        final NamedAclView view;
        try {
            view = namedAcls.view(email, selectedId);
        } catch (final RuntimeException ex) {
            editor.add(new Span(ex.getMessage()));
            return;
        }
        if (view == null) { editor.add(new Span("ACL not found.")); return; }

        // Label rename.
        final TextField label = new TextField("Label");
        label.setValue(view.label() == null ? "" : view.label());
        label.setWidthFull();
        final Button save = new Button("Rename", e -> {
            try {
                namedAcls.rename(email, selectedId, label.getValue());
                Notification.show("ACL renamed");
                refreshList();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        save.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        final HorizontalLayout labelRow = new HorizontalLayout(label, save);
        labelRow.setWidthFull();
        labelRow.setAlignItems(Alignment.END);
        editor.add(labelRow);

        if (view.system()) {
            final Span note = new Span("Reserved ACL — edits here change the platform-wide fallback.");
            note.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "12px").set("display", "block").set("margin", "6px 0 10px");
            editor.add(note);
        }

        for (final AceRow row : view.entries()) editor.add(aceRow(row));
        editor.add(addRow());
    }

    private Div aceRow(final AceRow aRow) {
        final Div r = new Div();
        r.getStyle().set("display", "flex").set("align-items", "center")
            .set("justify-content", "space-between").set("gap", "10px").set("padding", "6px 0")
            .set("border-bottom", "0.5px solid var(--lumo-contrast-5pct)");

        final Span who = new Span(accessorLabel(aRow));
        who.getStyle().set("font-size", "14px");
        r.add(who);

        final Div controls = new Div();
        controls.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px");

        final Select<String> level = new Select<>();
        level.setItems("owner".equals(aRow.accessorKind()) ? OWNER_LEVELS : LEVELS);
        level.setValue(aRow.basicLevel());
        level.setWidth("120px");
        level.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            try {
                namedAcls.setGrant(email, selectedId, aRow.accessorKind(), aRow.accessorId(),
                    e.getValue(), aRow.extPerms());
                Notification.show("ACL updated");
            } catch (final RuntimeException ex) {
                Notification.show(ex.getMessage());
                level.setValue(e.getOldValue());
            }
        });
        controls.add(level);

        final Button remove = new Button(VaadinIcon.TRASH.create(), e -> {
            try {
                namedAcls.removeGrant(email, selectedId, aRow.accessorKind(), aRow.accessorId());
                Notification.show("ACL updated");
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        remove.setAriaLabel("Remove");
        remove.setTooltipText("Remove");
        controls.add(remove);

        r.add(controls);
        return r;
    }

    /** The searchable add-accessor row (excludes accessors already granted). */
    private Div addRow() {
        final Div r = new Div();
        r.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px")
            .set("margin-top", "12px").set("padding-top", "12px")
            .set("border-top", "0.5px solid var(--lumo-contrast-10pct)");

        final ComboBox<AccessorOption> picker = new ComboBox<>();
        picker.setItems(namedAcls.availableAccessors(email, selectedId));
        picker.setItemLabelGenerator(AccessorOption::label);
        picker.setPlaceholder("Add a person, group, or role…");
        picker.getStyle().set("flex", "1");

        final Select<String> level = new Select<>();
        level.setItems(LEVELS);
        level.setValue("read");
        level.setWidth("120px");

        final Button add = new Button("Add", e -> {
            final AccessorOption sel = picker.getValue();
            if (sel == null) return;
            try {
                namedAcls.setGrant(email, selectedId, sel.accessorKind(), sel.accessorId(), level.getValue(), null);
                Notification.show("ACL updated");
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        add.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        r.add(picker, level, add);
        return r;
    }

    /** Create a new named ACL attached to a chosen org. */
    private void openNewDialog() {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New ACL");
        dialog.setWidth("400px");

        final TextField label = new TextField("Label");
        label.setWidthFull();
        label.setPlaceholder("e.g. Apologia Studios — curated");

        final ComboBox<OrgOption> org = new ComboBox<>("Attach to org");
        org.setItems(namedAcls.orgs(email));
        org.setItemLabelGenerator(OrgOption::name);
        org.setWidthFull();

        final VerticalLayout form = new VerticalLayout(label, org);
        form.setPadding(false);
        dialog.add(form);

        final Button create = new Button("Create", e -> {
            final OrgOption sel = org.getValue();
            if (sel == null) { Notification.show("Pick an org to attach the ACL to"); return; }
            try {
                final String newId = namedAcls.createForOrg(email, sel.groupId(), label.getValue());
                Notification.show("ACL created");
                dialog.close();
                selectedId = newId;
                refreshList();
                renderEditor();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), create);
        dialog.open();
    }

    private String accessorLabel(final AceRow aRow) {
        return switch (aRow.accessorKind()) {
            case "world" -> "Everyone";
            case "users" -> "Signed-in users";
            case "owner" -> "Owner";
            case "role"  -> aRow.accessorId();
            default      -> aRow.accessorLabel() != null ? aRow.accessorLabel() : aRow.accessorId();
        };
    }
}
