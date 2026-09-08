package org.religioustext.app.i18n;

import com.vaadin.flow.component.Direction;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinSession;
import org.religioustext.app.service.UserPreferencesService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Applies the session's chosen locale (English by default) and text direction
 * to every UI as it initialises — including the fresh UI produced by the page
 * reload that a language switch triggers, and restores the scroll position that
 * reload would otherwise throw away (see {@link #restoreScroll}). Without this, a non-English browser
 * Accept-Language header would make Vaadin pick that locale on first load; we
 * want a deterministic English default until the user chooses otherwise.
 */
@Component
public class LocaleInitListener implements VaadinServiceInitListener {

    private final UserPreferencesService prefsService;

    public LocaleInitListener(final UserPreferencesService aPrefsService) {
        this.prefsService = aPrefsService;
    }

    @Override
    public void serviceInit(final ServiceInitEvent anEvent) {
        anEvent.getSource().addUIInitListener(uiEvent -> {
            final UI ui = uiEvent.getUI();

            // A language in the URL overrides the session choice — lets a
            // shared link open the UI in a specific language. Path-based
            // (/fi, /reader/he) wins over ?lang=; both run before each view
            // renders, so a fresh navigation switches language without an
            // extra reload; unknown values are ignored.
            ui.addBeforeEnterListener(enter -> {
                final Locale fromPath = pathLocale(enter.getLocation());
                final List<String> vals = enter.getLocation()
                    .getQueryParameters().getParameters().get("lang");
                final Locale fromQuery = (vals == null || vals.isEmpty())
                    ? null : LocaleUtil.fromTag(vals.get(0));
                final Locale fromUrl = fromPath != null ? fromPath : fromQuery;
                if (fromUrl != null && !fromUrl.equals(LocaleUtil.currentLocale())) {
                    LocaleUtil.store(fromUrl);
                    apply(ui, fromUrl);
                }
            });

            // A signed-in user's saved language preference fills the gap when
            // the session holds no explicit choice yet. Explicit choices — the
            // dropdown or a ?lang= link (below) — always win over it.
            if (!LocaleUtil.hasStoredChoice()) {
                final Locale pref = preferredLocale();
                if (pref != null) LocaleUtil.store(pref);
            }

            apply(ui, LocaleUtil.currentLocale());
            restoreScroll(ui);
        });
    }

    /**
     * Put the reader back where they were after a language switch.
     *
     * <p>{@code LanguageSelect} reloads the page rather than re-rendering in
     * place — the locale and text direction are applied to a fresh UI — and a
     * reload lands at the top, which is disorienting when the switch was made
     * from the foot of a long page. That class records the scroll position as
     * a fraction of the scrollable distance just before it navigates; this
     * reads the fraction back and re-applies it, then clears it, so it fires
     * once per switch and never on an ordinary page load or a shared
     * {@code ?lang=} link.
     *
     * <p>A fraction rather than a pixel offset because the same page is a
     * different height in a different language; anchoring on the fraction of
     * MAXIMUM scroll means the bottom stays the bottom and the top stays the
     * top, with proportional drift in between. The view layouts scroll
     * themselves ({@code overflow-y: auto}) rather than the document, so the
     * element has to be found rather than assumed, and it is re-applied until
     * its height stops changing — Vaadin is still filling the page in when
     * this first runs.
     */
    private static void restoreScroll(final UI aUi) {
        aUi.getPage().executeJs(
            "const raw = sessionStorage.getItem('cr.scrollFraction');"
            + " if (raw === null) return;"
            + " sessionStorage.removeItem('cr.scrollFraction');"
            + " const frac = parseFloat(raw);"
            + " if (!(frac > 0)) return;"
            + " let el = null, ticks = 0, lastMax = -1, stable = 0;"
            + " const step = () => {"
            + "   let max = 0;"
            + "   if (!el) {"
            + "     for (const e of document.querySelectorAll('*')) {"
            + "       const t = e.tagName;"
            + "       if (t.startsWith('COPILOT') || t.startsWith('VAADIN-DEV')) continue;"
            + "       const d = e.scrollHeight - e.clientHeight;"
            + "       if (d > max && e.clientHeight > 100) { max = d; el = e; }"
            + "     }"
            + "   } else {"
            + "     max = el.scrollHeight - el.clientHeight;"
            + "   }"
            + "   if (el && max > 0) {"
            + "     el.scrollTop = frac * max;"
            + "     if (max === lastMax) { stable++; } else { stable = 0; lastMax = max; }"
            + "   }"
            + "   if (stable < 3 && ++ticks < 60) requestAnimationFrame(step);"
            + " };"
            + " requestAnimationFrame(step);");
    }

    /** The locale a language-specific path names ({@code /fi},
     *  {@code /reader/he}, {@code /edition/fi/AGR1548}), or null for every other
     *  location. Deliberately narrow — only shapes that are known to carry a
     *  language segment — so a route parameter that happens to look like a
     *  language tag (e.g. an edition code under {@code /read/...}) can never
     *  switch the UI.
     *
     *  <p>The edition shape is the one exception to "the language is the last
     *  segment", and it has to be here rather than left to the session: those
     *  URLs are in the sitemap and the hreflang cluster, so a shared
     *  {@code /edition/de/AGR1548} that opened with Finnish chrome around a
     *  German article would be advertising a page it does not serve. The
     *  language is segment 1, NOT the last — {@code /edition/tr/TR} is the
     *  Turkish page for the Textus Receptus, and matching on the last segment
     *  would read that abbreviation as a language. */
    private static Locale pathLocale(final Location aLocation) {
        final List<String> segs = aLocation.getSegments();
        if (segs.size() == 1) return LocaleUtil.fromTag(segs.get(0));
        if (segs.size() == 2 && "reader".equals(segs.get(0))) {
            return LocaleUtil.fromTag(segs.get(1));
        }
        if (segs.size() == 3 && "edition".equals(segs.get(0))) {
            return LocaleUtil.fromTag(segs.get(1));
        }
        return null;
    }

    /** The authenticated user's saved UI-language preference, or null (guest /
     *  none saved / unknown tag). */
    private Locale preferredLocale() {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) return null;
        try {
            return prefsService.find(auth.getName())
                .map(p -> LocaleUtil.fromTag(p.getUiLanguage()))
                .orElse(null);
        } catch (final Exception e) {
            return null;   // never let a prefs hiccup break UI init
        }
    }

    private static void apply(final UI aUi, final Locale aLocale) {
        aUi.setLocale(aLocale);
        final VaadinSession session = VaadinSession.getCurrent();
        if (session != null) {
            session.setLocale(aLocale);
        }
        aUi.setDirection(LocaleUtil.isRtl(aLocale)
            ? Direction.RIGHT_TO_LEFT
            : Direction.LEFT_TO_RIGHT);
        // Keep <html lang> in step for assistive tech and crawlers rendering
        // the page; toLanguageTag(), not getLanguage() (legacy "iw" for he).
        aUi.getPage().executeJs("document.documentElement.lang = $0",
            aLocale.toLanguageTag());
    }
}
