package org.religioustext.app.ui.views;

import java.util.Locale;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * The banner that admits a page has not been read by a human who speaks its
 * language.
 *
 * <p>Every non-English page on this site was translated by a machine. Two
 * native reviewers on r/translator and r/ChineseLanguage found that the short
 * UI strings mostly survived that and the long prose largely did not — one
 * reported that about half the paragraphs of the Chinese About page do not
 * make sense. Roughly 96% of the translated text by volume is that prose. A
 * reader who cannot check the English has no way to tell which sentences to
 * trust, so the page says so itself.
 *
 * <p>Two deliberate decisions:
 *
 * <p><b>It fails toward the notice.</b> Only an explicit {@code reviewed} in a
 * bundle's {@code i18n.status} suppresses it. A bundle that omits the key falls
 * back to the English base, which says {@code source}, which is not
 * {@code reviewed} — so a new or half-finished translation is marked as
 * unchecked by default rather than by someone remembering to mark it.
 *
 * <p><b>It is bilingual.</b> The notice is itself machine-translated until
 * somebody verifies it, which would make a monolingual warning quietly
 * self-undermining. The English sentence is always shown beneath, so the claim
 * is legible even if its translation is not.
 */
final class TranslationNotice {

    /** Where corrections go. Public repo, so anyone reading this can act on it. */
    private static final String CORRECTIONS_HREF =
        "https://github.com/christa-claw/common-root/issues";

    /** Minimal view of {@code Component.getTranslation(String, Locale)}. */
    @FunctionalInterface
    interface Translator {
        String get(String aKey, Locale aLocale);
    }

    private TranslationNotice() { }

    /**
     * The banner for a page rendered in {@code aLocale}, or {@code null} when
     * none is warranted — English pages, and languages someone has verified.
     *
     * @param anEnglishHref where the English original lives; may be null, in
     *                      which case no "read the original" link is offered
     */
    static Component forLocale(final Locale aLocale, final Translator aT,
                               final String anEnglishHref) {
        if (aLocale == null || "en".equals(aLocale.getLanguage())) return null;
        if ("reviewed".equals(aT.get("i18n.status", aLocale))) return null;

        final Div box = new Div();
        box.getStyle()
            .set("margin", "12px 16px")
            .set("padding", "10px 14px")
            .set("border-inline-start", "3px solid var(--lumo-contrast-30pct)")
            .set("border-radius", "4px")
            .set("background", "var(--lumo-contrast-5pct)")
            .set("font-size", "0.9em")
            .set("line-height", "1.45");

        final Div first = new Div(new Span(aT.get("notice.machineTranslated", aLocale)));
        if (anEnglishHref != null) {
            first.add(new Span(" "));
            first.add(link(anEnglishHref, aT.get("notice.machineTranslated.english", aLocale)));
        }
        first.add(new Span(" "));
        first.add(link(CORRECTIONS_HREF, aT.get("notice.machineTranslated.improve", aLocale)));
        box.add(first);

        final Div english = new Div(new Span(aT.get("notice.machineTranslated", Locale.ENGLISH)));
        english.getStyle()
            .set("margin-block-start", "4px")
            .set("font-size", "0.88em")
            .set("opacity", "0.7")
            // The English line is English whatever the page around it is doing.
            .set("direction", "ltr")
            .set("text-align", "start");
        english.getElement().setAttribute("lang", "en");
        box.add(english);
        return box;
    }

    private static Anchor link(final String aHref, final String aText) {
        final Anchor a = new Anchor(aHref, aText);
        a.getStyle().set("text-decoration", "underline");
        return a;
    }
}
