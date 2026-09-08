package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.service.ApiKeyService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The "API keys" block of the profile page (docs/api-spec.md §2): create a
 * key with a label, see it <em>once</em>, list what you hold, revoke.
 *
 * <p>The plaintext credential is shown in a dialog at creation and nowhere
 * else — the row that remains carries only the display prefix
 * ({@code crk_a81f02xx…}), because only a hash is stored. Revocation is a
 * one-click action with an in-page confirmation, never a browser dialog.
 * Up to {@value ApiKeyService#MAX_LIVE_KEYS} live keys, so rotation is
 * create-new, move traffic, revoke-old.
 *
 * <p>A plain component rather than a view: it belongs on the profile page
 * beside the display name, not behind its own route.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
public class ApiKeysSection extends VerticalLayout {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ApiKeyService keys;
    private final String        userId;
    private final Grid<ApiKey>  grid = new Grid<>();
    private final TextField     label = new TextField();
    private final Button        create;

    public ApiKeysSection(final ApiKeyService anApiKeyService, final String aUserId) {
        this.keys   = anApiKeyService;
        this.userId = aUserId;

        setPadding(false);
        setSpacing(false);
        setWidth("640px");
        setMaxWidth("100%");

        final H3 title = new H3("API keys");
        title.getStyle().set("margin", "24px 0 4px 0");

        final Paragraph help = new Paragraph();
        help.add("Keys let scripts and other sites use the corpus through the API — reading "
               + "the site itself never needs one. A key carries a monthly allowance; it never "
               + "changes what you are entitled to. See the ");
        help.add(new Anchor("/api/docs", "API documentation"));
        help.add(".");
        help.getStyle().set("font-size", "13px").set("color", "var(--lumo-secondary-text-color)")
            .set("margin", "0 0 12px 0");

        grid.addColumn(k -> ApiKeyService.PREFIX + k.getKeyId() + "…").setHeader("Key").setAutoWidth(true);
        grid.addColumn(k -> k.getLabel() == null ? "" : k.getLabel()).setHeader("Label").setAutoWidth(true);
        grid.addColumn(k -> fmt(k.getCreatedAt())).setHeader("Created").setAutoWidth(true);
        grid.addColumn(k -> k.getLastUsedAt() == null ? "never" : fmt(k.getLastUsedAt())).setHeader("Last used").setAutoWidth(true);
        grid.addComponentColumn(this::actionFor).setHeader("").setAutoWidth(true);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();

        label.setPlaceholder("Label (e.g. laptop, CI)");
        label.setMaxLength(64);
        label.setWidth("260px");
        create = new Button("Create key", e -> createKey());
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        final HorizontalLayout createRow = new HorizontalLayout(label, create);
        createRow.setAlignItems(Alignment.BASELINE);
        createRow.getStyle().set("margin-top", "8px");

        add(title, help, grid, createRow);
        refresh();
    }

    private void refresh() {
        final List<ApiKey> all = keys.listFor(userId);
        grid.setItems(all);
        final long live = all.stream().filter(k -> !k.isRevoked()).count();
        create.setEnabled(live < ApiKeyService.MAX_LIVE_KEYS);
        create.setTooltipText(live < ApiKeyService.MAX_LIVE_KEYS ? null
            : "You hold " + ApiKeyService.MAX_LIVE_KEYS + " live keys; revoke one to create another.");
    }

    private com.vaadin.flow.component.Component actionFor(final ApiKey aKey) {
        if (aKey.isRevoked()) {
            final Span revoked = new Span("revoked " + fmt(aKey.getRevokedAt()));
            revoked.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "13px");
            return revoked;
        }
        final Button revoke = new Button("Revoke", e -> confirmRevoke(aKey));
        revoke.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
        return revoke;
    }

    private void createKey() {
        final ApiKeyService.CreatedKey created;
        try {
            created = keys.create(userId, label.getValue());
        } catch (final IllegalStateException ex) {
            Notification.show(ex.getMessage(), 5000, Notification.Position.TOP_CENTER);
            return;
        }
        label.clear();
        refresh();
        showOnce(created.key(), created.record());
    }

    /** The one moment the plaintext exists on a screen. */
    private void showOnce(final String aPlaintext, final ApiKey aRecord) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Your new API key");
        dialog.setCloseOnOutsideClick(false);

        final Paragraph warn = new Paragraph("Copy it now — it is shown once and cannot be retrieved "
            + "again. Only a hash is stored; if you lose it, revoke it and create another.");
        warn.getStyle().set("font-size", "13px").set("max-width", "440px");

        final TextField shown = new TextField();
        shown.setValue(aPlaintext);
        shown.setReadOnly(true);
        shown.setWidth("440px");
        shown.getStyle().set("font-family", "monospace");

        final Button copy = new Button("Copy", e ->
            // Clipboard writes are only allowed inside the click gesture, client-side.
            shown.getElement().executeJs("navigator.clipboard.writeText($0)", aPlaintext)
                .then(r -> Notification.show("Copied.", 2000, Notification.Position.TOP_CENTER)));
        copy.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Paragraph usage = new Paragraph("Send it as a header: Authorization: Bearer " + ApiKeyService.PREFIX
            + aRecord.getKeyId() + "…  — never in a URL.");
        usage.getStyle().set("font-size", "12px").set("color", "var(--lumo-secondary-text-color)");

        final Button done = new Button("I have copied it", e -> dialog.close());
        done.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        final HorizontalLayout row = new HorizontalLayout(shown, copy);
        row.setAlignItems(Alignment.BASELINE);
        dialog.add(new VerticalLayout(warn, row, usage));
        dialog.getFooter().add(done);
        dialog.open();
    }

    private void confirmRevoke(final ApiKey aKey) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Revoke " + ApiKeyService.PREFIX + aKey.getKeyId() + "…?");
        final Paragraph body = new Paragraph("Anything using this key stops working immediately. "
            + "This cannot be undone; create a new key first if you are rotating.");
        body.getStyle().set("max-width", "420px").set("font-size", "13px");
        final Button revoke = new Button("Revoke", e -> {
            keys.revoke(userId, aKey.getId());
            dialog.close();
            refresh();
            Notification.show("Key revoked.", 3000, Notification.Position.TOP_CENTER);
        });
        revoke.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        final Button cancel = new Button("Keep it", e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dialog.add(body);
        dialog.getFooter().add(cancel, revoke);
        dialog.open();
    }

    private static String fmt(final LocalDateTime aMoment) {
        return aMoment == null ? "" : DATE.format(aMoment);
    }
}
