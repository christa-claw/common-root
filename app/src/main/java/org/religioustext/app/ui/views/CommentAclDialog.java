// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.select.Select;
import org.religioustext.app.service.CommentAclService;
import org.religioustext.app.service.CommentAclService.AccessorOption;
import org.religioustext.app.service.CommentAclService.AclView;
import org.religioustext.app.service.CommentAclService.AceRow;

import java.util.List;
import java.util.function.Function;

/**
 * The comment-context ACL editor (mode 2, docs/access-control.md §4a). Lists the comment's
 * effective ACEs; changing an accessor's level routes through {@link CommentAclService} which
 * copy-on-writes a custom ACL for this comment. Read-only when the caller can't change it.
 * Editing existing grants only for now — adding a brand-new accessor (user/group picker) is a
 * follow-up.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
public class CommentAclDialog extends Dialog {

    private static final List<String> LEVELS = List.of("none", "browse", "read", "write", "delete");
    /** The owner floor is {@code read}: an owner can be reduced (for retention) but never below
     *  read, so the owner row omits {@code none}/{@code browse}. */
    private static final List<String> OWNER_LEVELS = List.of("read", "write", "delete");

    private final CommentAclService commentAclService;
    private final Function<String, String> translator;
    private final String publicId;
    private final String email;
    private final Runnable afterChange;

    public CommentAclDialog(final CommentAclService aCommentAclService,
                            final Function<String, String> aTranslator,
                            final String aPublicId, final String anEmail,
                            final Runnable afterTheChange) {
        this.commentAclService = aCommentAclService;
        this.translator = aTranslator;
        this.publicId = aPublicId;
        this.email = anEmail;
        this.afterChange = afterTheChange == null ? () -> { } : afterTheChange;

        setHeaderTitle(translator.apply("reader.acl.title"));
        setWidth("460px");
        setMaxWidth("94vw");
        setDraggable(true);
        setResizable(true);
        final Button close = new Button(translator.apply("action.close"), e -> close());
        getFooter().add(close);
        build();
    }

    private void build() {
        removeAll();
        final AclView view;
        try {
            view = commentAclService.viewFor(email, publicId);
        } catch (final RuntimeException ex) {
            add(new Span(ex.getMessage()));
            return;
        }

        // Source line: custom vs inherited.
        final Span source = new Span(view.custom()
            ? translator.apply("reader.acl.custom")
            : translator.apply("reader.acl.inherited") + (view.sourceLabel() != null ? " — " + view.sourceLabel() : ""));
        source.getStyle().set("color", "var(--lumo-secondary-text-color)")
            .set("font-size", "13px").set("display", "block").set("margin-bottom", "10px");
        add(source);

        for (final AceRow row : view.entries()) add(aceRow(row, view.canChange()));

        if (view.canChange()) add(addRow());

        if (!view.canChange()) {
            final Span ro = new Span(translator.apply("reader.acl.readonly"));
            ro.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-size", "12px").set("display", "block").set("margin-top", "10px");
            add(ro);
        }

        // Reset-to-inherited (only meaningful for a custom ACL the caller can change).
        getFooter().removeAll();
        if (view.custom() && view.canChange()) {
            final Button reset = new Button(translator.apply("reader.acl.reset"), e -> {
                try {
                    commentAclService.reset(email, publicId);
                    Notification.show(translator.apply("reader.acl.updated"));
                    afterChange.run();
                    close();
                } catch (final RuntimeException ex) {
                    Notification.show(ex.getMessage());
                }
            });
            reset.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            getFooter().add(reset);
        }
        getFooter().add(new Button(translator.apply("action.close"), e -> close()));
    }

    private Div aceRow(final AceRow aRow, final boolean theCanChangeFlag) {
        final Div r = new Div();
        r.getStyle().set("display", "flex").set("align-items", "center")
            .set("justify-content", "space-between").set("gap", "10px").set("padding", "4px 0");

        final Span who = new Span(accessorLabel(aRow));
        who.getStyle().set("font-size", "14px");
        r.add(who);

        final Div controls = new Div();
        controls.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px");

        final Select<String> level = new Select<>();
        // Owner row is floored at read (never none/browse); everyone else gets the full ladder.
        level.setItems("owner".equals(aRow.accessorKind()) ? OWNER_LEVELS : LEVELS);
        level.setValue(aRow.basicLevel());
        level.setWidth("120px");
        level.setReadOnly(!theCanChangeFlag);
        // Guard: only react to user changes (setValue above would otherwise re-save on rebuild).
        level.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;
            try {
                commentAclService.setGrant(email, publicId, aRow.accessorKind(), aRow.accessorId(),
                    e.getValue(), aRow.extPerms());
                Notification.show(translator.apply("reader.acl.updated"));
                afterChange.run();
                build();   // grant may have flipped inherited → custom
            } catch (final RuntimeException ex) {
                Notification.show(ex.getMessage());
                level.setValue(e.getOldValue());
            }
        });
        controls.add(level);

        if (theCanChangeFlag) {
            final Button remove = new Button(VaadinIcon.TRASH.create(), e -> {
                try {
                    commentAclService.removeGrant(email, publicId, aRow.accessorKind(), aRow.accessorId());
                    Notification.show(translator.apply("reader.acl.updated"));
                    afterChange.run();
                    build();
                } catch (final RuntimeException ex) {
                    Notification.show(ex.getMessage());
                }
            });
            remove.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            remove.setAriaLabel(translator.apply("reader.acl.remove"));
            remove.setTooltipText(translator.apply("reader.acl.remove"));
            controls.add(remove);
        }
        r.add(controls);
        return r;
    }

    /** The searchable "add an accessor" row: pick any accessor + a level → new grant. */
    private Div addRow() {
        final Div r = new Div();
        r.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px")
            .set("margin-top", "12px").set("padding-top", "12px")
            .set("border-top", "0.5px solid var(--lumo-contrast-10pct)");

        final ComboBox<AccessorOption> picker = new ComboBox<>();
        picker.setItems(commentAclService.availableAccessors(publicId));
        picker.setItemLabelGenerator(AccessorOption::label);
        picker.setPlaceholder(translator.apply("reader.acl.pickAccessor"));
        picker.getStyle().set("flex", "1");

        final Select<String> level = new Select<>();
        level.setItems(LEVELS);
        level.setValue("read");
        level.setWidth("120px");

        final Button add = new Button(translator.apply("reader.acl.add"), e -> {
            final AccessorOption sel = picker.getValue();
            if (sel == null) return;
            try {
                commentAclService.setGrant(email, publicId, sel.accessorKind(), sel.accessorId(), level.getValue(), null);
                Notification.show(translator.apply("reader.acl.updated"));
                afterChange.run();
                build();
            } catch (final RuntimeException ex) {
                Notification.show(ex.getMessage());
            }
        });
        add.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        r.add(picker, level, add);
        return r;
    }

    private String accessorLabel(final AceRow aRow) {
        return switch (aRow.accessorKind()) {
            case "world" -> translator.apply("reader.acl.accessor.world");
            case "users" -> translator.apply("reader.acl.accessor.users");
            case "owner" -> translator.apply("reader.acl.accessor.owner");
            case "role"  -> aRow.accessorId();                       // e.g. ROLE_ADMIN
            default      -> aRow.accessorLabel() != null ? aRow.accessorLabel() : aRow.accessorId();
        };
    }
}
