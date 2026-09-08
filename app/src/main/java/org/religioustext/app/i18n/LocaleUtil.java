package org.religioustext.app.i18n;

import com.vaadin.flow.server.VaadinSession;

import java.util.List;
import java.util.Locale;

/**
 * Central definition of the locales the UI supports and the session-scoped
 * "current language" choice. English is the default/fallback for everything.
 *
 * The chosen locale is stored as a VaadinSession attribute (not the browser's
 * Accept-Language) so the UI defaults to English until the user explicitly picks
 * another language from the {@code LanguageSelect} dropdown. {@code LocaleInitListener}
 * applies it (locale + text direction) on every UI init, including after the
 * page reload that a language switch triggers.
 */
public final class LocaleUtil {

    private LocaleUtil() { }

    public static final Locale EN = Locale.ENGLISH;
    public static final Locale AR = Locale.forLanguageTag("ar");
    public static final Locale ES = Locale.forLanguageTag("es");
    public static final Locale FI = Locale.forLanguageTag("fi");
    public static final Locale SV = Locale.forLanguageTag("sv");
    public static final Locale RU = Locale.forLanguageTag("ru");
    public static final Locale ZH = Locale.forLanguageTag("zh");
    public static final Locale FR = Locale.forLanguageTag("fr");
    public static final Locale IT = Locale.forLanguageTag("it");
    public static final Locale DE = Locale.forLanguageTag("de");
    public static final Locale HI = Locale.forLanguageTag("hi");
    public static final Locale HE = Locale.forLanguageTag("he");
    public static final Locale TR = Locale.forLanguageTag("tr");

    /** Provided locales, English first (the default). */
    public static final List<Locale> LOCALES = List.of(EN, AR, ES, FI, SV, RU, ZH, FR, IT, DE, HI, HE, TR);

    private static final String SESSION_KEY = "cr.locale";

    /** The user's chosen locale for this session, or English if none chosen. */
    public static Locale currentLocale() {
        final VaadinSession s = VaadinSession.getCurrent();
        final Object v = (s != null) ? s.getAttribute(SESSION_KEY) : null;
        return (v instanceof Locale loc && LOCALES.contains(loc)) ? loc : EN;
    }

    /** Whether this session holds an explicit language choice (dropdown, ?lang=,
     *  or an applied user preference) — distinguishes "defaulting to English"
     *  from "chose English", so a saved preference only fills the former. */
    public static boolean hasStoredChoice() {
        final VaadinSession s = VaadinSession.getCurrent();
        return s != null && s.getAttribute(SESSION_KEY) instanceof Locale;
    }

    /** Persist the chosen locale for this session. */
    public static void store(final Locale aLocale) {
        final VaadinSession s = VaadinSession.getCurrent();
        if (s != null && LOCALES.contains(aLocale)) {
            s.setAttribute(SESSION_KEY, aLocale);
        }
    }

    /** Resolve a ?lang= query value ("fi", "he", "zh", case-insensitive, also
     *  tolerating region subtags like "fi-FI") to a provided locale, or null.
     *  "iw" is accepted as legacy Hebrew. */
    public static Locale fromTag(final String aTag) {
        if (aTag == null || aTag.isBlank()) return null;
        String lang = aTag.trim().toLowerCase(Locale.ROOT);
        final int dash = lang.indexOf('-');
        if (dash > 0) lang = lang.substring(0, dash);
        if ("iw".equals(lang)) lang = "he";
        for (final Locale loc : LOCALES) {
            if (loc.getLanguage().equals(lang)
                    || ("he".equals(lang) && "iw".equals(loc.getLanguage()))) {
                return loc;
            }
        }
        return null;
    }

    /** Whether a locale is written right-to-left (Arabic and Hebrew, here). */
    public static boolean isRtl(final Locale aLocale) {
        if (aLocale == null) return false;
        final String lang = aLocale.getLanguage();
        // Locale normalises "he" to the legacy ISO code "iw" on some JDKs.
        return "ar".equals(lang) || "he".equals(lang) || "iw".equals(lang);
    }
}
