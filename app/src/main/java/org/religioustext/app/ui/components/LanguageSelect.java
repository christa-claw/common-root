// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.components;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.select.Select;
import org.religioustext.app.i18n.LocaleUtil;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * UI language switcher: a dropdown listing each supported language by its own
 * native name with a flag icon (English, العربية, Español, Suomi, Svenska,
 * Русский, 中文). Picking one stores the choice in the session and reloads the
 * page; {@code LocaleInitListener} then applies the locale and text direction to
 * the rebuilt UI, and restores the scroll position this class records just
 * before the reload.
 *
 * <p><b>Where the reload goes.</b> Two strategies, and a page that knows its own
 * URL shape should use the first:
 *
 * <ul>
 *   <li>{@link #LanguageSelect(Function)} — the page supplies the destination
 *       for each locale, computed server-side. Necessary wherever the URL, not
 *       the session, decides what language the CONTENT is in.</li>
 *   <li>{@link #LanguageSelect()} — no destination supplied, so this class
 *       rewrites the URL itself: swap a trailing language segment ({@code /fi},
 *       {@code /reader/he}), append one to {@code /} or {@code /reader}, else
 *       fall back to {@code ?lang=}. Fine for pages whose body is translated
 *       through the message bundles, where the session alone settles it.</li>
 * </ul>
 *
 * <p>The rewriting strategy is a guess, and {@code EditionInfoView} is where it
 * guessed wrong: {@code /edition/fi/AGR1548} carries its language in the MIDDLE
 * of the path, so the trailing-segment rule missed it and the switch degraded to
 * {@code ?lang=de} — which moved the chrome and the text direction while the
 * path went on selecting the Finnish article, leaving half a page in each
 * language. Client-side rewriting cannot fix that case on its own: choosing
 * {@code /edition/de/DIO} needs to know whether a German DIO page was ever
 * written, and only the server knows that ({@code EditionInfo.slugFor}).
 *
 * NOTE on flags: flag EMOJI are used for now (zero assets). They render as flags
 * on macOS/iOS/Android but NOT on most Windows browsers (which show the country
 * code letters). Swap to inline SVG flag assets if Windows rendering matters.
 * Also note a flag denotes a country, not a language — an imperfect-but-common
 * convention the UI deliberately follows here per the design request.
 */
public class LanguageSelect extends Select<Locale> {

    private static final Map<Locale, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(LocaleUtil.EN, "\uD83C\uDDEC\uD83C\uDDE7 English");   // 🇬🇧
        LABELS.put(LocaleUtil.AR, "\uD83C\uDDF8\uD83C\uDDE6 العربية");  // 🇸🇦
        LABELS.put(LocaleUtil.ES, "\uD83C\uDDEA\uD83C\uDDF8 Español");   // 🇪🇸
        LABELS.put(LocaleUtil.FI, "\uD83C\uDDEB\uD83C\uDDEE Suomi");     // 🇫🇮
        LABELS.put(LocaleUtil.SV, "\uD83C\uDDF8\uD83C\uDDEA Svenska");   // 🇸🇪
        LABELS.put(LocaleUtil.RU, "\uD83C\uDDF7\uD83C\uDDFA Русский");   // 🇷🇺
        LABELS.put(LocaleUtil.ZH, "\uD83C\uDDE8\uD83C\uDDF3 中文");       // 🇨🇳
        LABELS.put(LocaleUtil.FR, "\uD83C\uDDEB\uD83C\uDDF7 Français");  // 🇫🇷
        LABELS.put(LocaleUtil.IT, "\uD83C\uDDEE\uD83C\uDDF9 Italiano");  // 🇮🇹
        LABELS.put(LocaleUtil.DE, "\uD83C\uDDE9\uD83C\uDDEA Deutsch");   // 🇩🇪
        LABELS.put(LocaleUtil.HI, "\uD83C\uDDEE\uD83C\uDDF3 हिन्दी");    // 🇮🇳
        LABELS.put(LocaleUtil.HE, "\uD83C\uDDEE\uD83C\uDDF1 עברית");     // 🇮🇱
        LABELS.put(LocaleUtil.TR, "\uD83C\uDDF9\uD83C\uDDF7 Türkçe");    // 🇹🇷
    }

    /**
     * Remember how far down the page the reader is BEFORE the reload, so
     * {@code LocaleInitListener} can put them back there. Stored as a fraction
     * of the scrollable distance rather than a pixel offset: the same page is a
     * different height in a different language, and "at the bottom" should stay
     * at the bottom. The scrolling element is a view's own layout (they set
     * overflow-y: auto on themselves), not the document, so window.scrollY is
     * always 0 here and the browser's native scroll restoration cannot help —
     * hence finding it by hand.
     *
     * <p>Prefixed to whichever navigation follows, so both strategies above
     * keep the reader's place.
     */
    private static final String RECORD_SCROLL =
        "try {"
        + "   let el = null, max = 0;"
        + "   for (const e of document.querySelectorAll('*')) {"
        + "     const t = e.tagName;"
        + "     if (t.startsWith('COPILOT') || t.startsWith('VAADIN-DEV')) continue;"
        + "     const d = e.scrollHeight - e.clientHeight;"
        + "     if (d > max && e.clientHeight > 100) { max = d; el = e; }"
        + "   }"
        + "   const y = el ? el.scrollTop : window.scrollY;"
        + "   const m = el ? max"
        + "     : (document.documentElement.scrollHeight - window.innerHeight);"
        + "   sessionStorage.setItem('cr.scrollFraction',"
        + "     m > 0 ? String(y / m) : '0');"
        + " } catch (ignored) { }";

    /** The URL-rewriting switcher — see the class javadoc for when that is
     *  and is not enough. */
    public LanguageSelect() {
        this(null);
    }

    /**
     * @param aTargetPath given a chosen locale, the path the reload should land
     *                    on — root-relative, query string and all. Consulted
     *                    once, server-side, for the locale actually picked, so
     *                    it can use whatever the server knows that the browser
     *                    does not. May be null (and may return null for a
     *                    particular locale) to take the rewriting path instead.
     */
    public LanguageSelect(final Function<Locale, String> aTargetPath) {
        setItems(LocaleUtil.LOCALES);
        setItemLabelGenerator(loc -> LABELS.getOrDefault(loc, loc.getDisplayName()));
        setValue(LocaleUtil.currentLocale());
        getStyle().set("min-width", "132px").set("flex-shrink", "0");
        addValueChangeListener(e -> {
            if (!e.isFromClient() || e.getValue() == null) {
                return;
            }
            LocaleUtil.store(e.getValue());

            // A page that knows where this language lives: go straight there,
            // no parsing of the current URL at all.
            final String target =
                aTargetPath == null ? null : aTargetPath.apply(e.getValue());
            if (target != null) {
                UI.getCurrent().getPage().executeJs(
                    RECORD_SCROLL + " location.assign($0);", target);
                return;
            }

            // Reload with the choice in the URL so the address is shareable
            // as-is (LocaleInitListener honours it on the way back in). The
            // path form (/fi, /reader/he) is preferred wherever it exists —
            // it outranks ?lang= anyway — so: rewrite a language segment in
            // place, or append one to / and /reader. Only routes with no
            // language path variant (/read/:code, admin...) fall back to
            // ?lang=<tag>.
            // toLanguageTag(), not getLanguage(): the latter is "iw" for Hebrew.
            UI.getCurrent().getPage().executeJs(
                RECORD_SCROLL
                + " const u = new URL(location.href);"
                + " const langs = $1.split(',');"
                + " const segs = u.pathname.split('/').filter(s => s);"
                + " if (segs.length && langs.includes(segs[segs.length - 1])) {"
                + "   segs[segs.length - 1] = $0;"        // /fi, /reader/he
                + " } else if (segs.length === 0"          // /
                + "         || (segs.length === 1 && segs[0] === 'reader')) {"
                + "   segs.push($0);"                      // -> /fi, /reader/fi
                + " } else {"
                + "   u.searchParams.set('lang', $0);"     // no path variant
                + "   location.assign(u.toString());"
                + "   return;"
                + " }"
                + " u.pathname = '/' + segs.join('/');"
                + " u.searchParams.delete('lang');"
                + " location.assign(u.toString());",
                e.getValue().toLanguageTag(),
                String.join(",", LocaleUtil.LOCALES.stream()
                    .map(Locale::toLanguageTag).toList()));
        });
    }
}
