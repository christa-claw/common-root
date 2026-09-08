// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.religioustext.app.i18n.LocaleUtil;

/**
 * Data behind the {@code /edition/:abbr} bibliographic info pages — "who
 * translated this, when, where, from what" for a single edition, reached via
 * a small "ℹ️" icon on that edition's card in {@link AboutView}'s translation
 * grids (see {@code AboutView#addTextCard}, which looks this map up by
 * abbreviation and renders the icon ONLY when an entry exists here).
 *
 * <p>Deliberately a data map, not one Java class per edition — same
 * philosophy as {@link LandingPage} (language landing pages), {@code
 * patch_lineage.py}, and {@code channels.properties} elsewhere in this
 * project: adding an edition's info page is adding an entry here, not
 * writing new code.
 *
 * <p>Distinct in PURPOSE from {@link LandingPage}: that class is built to
 * independently RANK for a language-level search query ("hindi bible
 * online") and deliberately avoids one page per edition, to not
 * keyword-cannibalize across an edition's siblings. This class is a plain
 * informational interstitial for someone already on the site, browsing a
 * translation grid — every edition can eventually have one without that
 * concern. Kept in its own {@code /edition/:abbr} route namespace so the
 * two purposes never tangle (see {@link EditionInfoView}).
 *
 * <p>Content does NOT live here. Each edition's prose is a set of properties
 * files under {@code src/main/resources/i18n/editions} — one per edition per
 * language, {@code GNV.properties} (English, the base bundle) beside
 * {@code GNV_fi.properties} — and this class loads them into {@link #PAGES}.
 *
 * <p>It used to live here, justified by analogy with {@link LandingPage}. The
 * analogy was false. {@code LandingPage}'s reason is that one landing page is
 * one language is one purpose-written text, "so there is nothing to translate
 * INTO" — which is exactly wrong for this class, where every English entry is
 * translated into twelve others and that IS the recurring task. Two things
 * followed from moving them out: a file can be handed to somebody who reads
 * the language and not Java, edited and sent back with no way to break the
 * build (several of these pages are better because a philologist did read
 * them — see {@link #thanks}); and the prose is raw UTF-8, since property
 * bundles are read as UTF-8, so the {@code \\uXXXX} escaping that inline Java
 * string literals forced on Arabic, Hebrew, Hindi and Chinese is gone.
 *
 * <p>The move also brought this class back under the standing class-size rule
 * (scripts/site/check_class_size.py) — inline, it had reached 13,545 lines against a
 * 2,000-line hard ceiling.
 *
 * <p><b>Filling this in is intended to be a recurring, one-at-a-time task</b>
 * — see the scheduled prompt filed 2026-07-28. Each run should pick the next
 * abbreviation NOT yet covered (cross-reference {@code AboutView}'s
 * {@code addTextCard} calls for the full roster), draft its entry using
 * well-established, uncontested facts (manuscript basis, translator/
 * committee, year, place, textual tradition), and present it for review —
 * per the project's standing rule, DO NOT commit without that review. A new
 * edition is now a set of files under {@code i18n/editions} plus its
 * abbreviation in that directory's {@code index.properties}; no code changes.
 * {@code scripts/site/check_edition_info.py} verifies a translation against
 * its English original and reports coverage.
 */
public final class EditionInfo {

    /** The edition abbreviation, exactly as passed to {@code addTextCard}
     *  (e.g. "WLC") — the map key is this same string, so lookups are a
     *  direct {@code PAGES.get(anAbbr)}, no normalisation needed. */
    public final String abbr;
    /** BCP-47 language of THIS page's prose: "en", "fi", … */
    public final String lang;
    /** The path segment(s) after {@link #PREFIX} — "WLC" for English, "fi/WLC"
     *  for a translation. English deliberately has no language segment, so the
     *  URLs already published stay valid and need no redirects. This is the map
     *  key, so {@link #forPathInfo} stays a direct lookup. */
    public final String slug;
    public final String title;              // <title> / og:title for the info page
    public final String description;        // <meta name=description> / og:description
    public final String heroHeading;        // the edition's full name, as a heading
    public final String heroBody;           // the narrative — who/when/where/why it matters; \n\n-separated paragraphs
    /** Short label → value facts strip (translator, date, source manuscript,
     *  textual tradition, etc.) — order is display order. */
    public final Map<String, String> facts;
    public final String readerHref;         // deep link into the reader on this edition
    /**
     * A one-line thank-you under the facts strip, in this page's own language,
     * or {@code null} on the pages nobody has corrected yet.
     *
     * <p>Small and quiet on purpose. Several of these pages are better than they
     * were because a philologist read them for nothing and wrote back — Tanja
     * Toropainen on Agricola, Maria Lehtonen on all four Finnish editions — and a
     * page that takes such help without a word of thanks is the poorer for it.
     * It stays a THANKS and not a credential: no titles, no institutions, no
     * "reviewed by". They gave corrections, not endorsement, and the line should
     * say the first without implying the second.
     */
    public final String thanks;

    EditionInfo(final String anAbbr, final String aLang,
                final String aTitle, final String aDescription,
                final String aHeroHeading, final String aHeroBody,
                final Map<String, String> theFacts, final String aReaderHref) {
        this(anAbbr, aLang, aTitle, aDescription, aHeroHeading, aHeroBody,
             theFacts, aReaderHref, null);
    }

    EditionInfo(final String anAbbr, final String aLang,
                final String aTitle, final String aDescription,
                final String aHeroHeading, final String aHeroBody,
                final Map<String, String> theFacts, final String aReaderHref,
                final String aThanks) {
        abbr = anAbbr;
        lang = aLang;
        slug = "en".equals(aLang) ? anAbbr : aLang + "/" + anAbbr;
        title = aTitle;
        description = aDescription;
        heroHeading = aHeroHeading;
        heroBody = aHeroBody;
        facts = theFacts;
        readerHref = aReaderHref;
        thanks = aThanks;
    }

    // ── The pages ─────────────────────────────────────────────────────
    // Empty at class-init and filled entirely from properties files by
    // loadBundles() below. Nothing is written here any more.

    // Declared before the static block below, not beside the loader they
    // belong to: static initialisers run in textual order, so a constant the
    // loader dereferences must already be assigned when the block runs.
    private static final String BUNDLE_ROOT = "i18n.editions.";
    private static final String INDEX = "/i18n/editions/index.properties";

    /** Bundles are looked up with default-locale fallback OFF. With it on, a
     *  JVM whose default locale happened to be Finnish would serve the Finnish
     *  file as the English page. */
    private static final ResourceBundle.Control NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT);

    public static final Map<String, EditionInfo> PAGES = new LinkedHashMap<>();
    static {
        loadBundles();
    }

    // ── Content loaded from properties ─────────────────────────
    //
    // Editions listed in i18n/editions/index.properties keep their prose in
    // src/main/resources/i18n/editions instead of in the static block above:
    // one file per edition per language, GNV.properties (English, the base
    // bundle) alongside GNV_fi.properties and the rest.
    //
    // The point is that a file can be sent to somebody who reads the language
    // and does not read Java, edited, and sent back, with no possibility of
    // breaking the build — the entries above cannot be handled that way. The
    // files are raw UTF-8 (Java 9+ reads property bundles as UTF-8), so the
    // \\uXXXX escaping the inline entries need does not apply to them either.
    //
    // Migration is one edition at a time, exactly as the drafting is: an
    // abbreviation is served from here the moment it appears in index.properties
    // and its files exist, and from the map above until then.

    /** The abbreviations whose content has moved to properties files. Read from
     *  a resource rather than hard-coded so migrating one more edition stays a
     *  matter of adding files and a name to a list. */
    private static List<String> migrated() {
        final Properties index = new Properties();
        try (InputStream in = EditionInfo.class.getResourceAsStream(INDEX)) {
            if (in == null) {
                return List.of();
            }
            index.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            return List.of();   // never let a missing resource break class init
        }
        return Arrays.stream(index.getProperty("editions", "").split(","))
            .map(String::trim)
            .filter(seg -> !seg.isEmpty())
            .toList();
    }

    private static void loadBundles() {
        for (final String abbr : migrated()) {
            // LOCALES order, so English is inserted first and
            // variantsOf keeps its English-first contract.
            for (final Locale locale : LocaleUtil.LOCALES) {
                final EditionInfo page = fromBundle(abbr, locale);
                if (page != null) {
                    PAGES.put(page.slug, page);
                }
            }
        }
    }

    /**
     * One page from {@code i18n/editions/<ABBR>[_<lang>].properties}, or null
     * when that language has no file of its own.
     *
     * <p>The null case matters: {@code getBundle} answers a request for a
     * missing language with the English base bundle, and accepting that would
     * invent a translated page that was never written — advertising it in the
     * sitemap and the hreflang cluster, and hiding the gap from the coverage
     * report. So the bundle actually loaded has to BE the one asked for.
     */
    private static EditionInfo fromBundle(final String anAbbr, final Locale aLocale) {
        final ResourceBundle bundle;
        try {
            // Explicit class loader: the (String, Locale, Control) overload derives
            // one from the calling class, which is not dependable when this class is
            // loaded outside the app (the check script's reflection harness) or under
            // Spring Boot's nested loader.
            bundle = ResourceBundle.getBundle(BUNDLE_ROOT + anAbbr, aLocale
                                              , EditionInfo.class.getClassLoader()
                                              , NO_FALLBACK);
        } catch (final MissingResourceException e) {
            return null;
        }
        final String wanted = "en".equals(aLocale.getLanguage())
            ? "" : aLocale.getLanguage();          // the base bundle is English
        if (!wanted.equals(bundle.getLocale().getLanguage())) {
            return null;
        }

        final StringBuilder hero = new StringBuilder();
        for (int i = 1; bundle.containsKey("hero." + i); i++) {
            if (i > 1) {
                hero.append("\n\n");
            }
            hero.append(bundle.getString("hero." + i));
        }
        final Map<String, String> facts = new LinkedHashMap<>();
        for (int i = 1; bundle.containsKey("fact." + i + ".label"); i++) {
            facts.put(bundle.getString("fact." + i + ".label"),
                      bundle.getString("fact." + i + ".value"));
        }
        return new EditionInfo(anAbbr, aLocale.getLanguage()
                               , bundle.getString("title")
                               , bundle.getString("description")
                               , bundle.getString("heroHeading")
                               , hero.toString()
                               , facts
                               , bundle.getString("readerHref")
                               , bundle.containsKey("thanks")
                                   ? bundle.getString("thanks") : null);
    }

    // ── Path resolution ────────────────────────────────────────

    /** URL prefix these pages live under. Mirrors {@link LandingPage#PREFIX}'s
     *  contract exactly — see {@link #forPathInfo} for why. */
    public static final String PREFIX = "/edition/";

    /**
     * Resolve a raw request path info to the edition-info page it addresses,
     * or {@code null} if it addresses none.
     *
     * <p>Both SEO listeners must route on this one method, exactly as they do
     * on {@link LandingPage#forPathInfo} — {@code LandingSeoListener} to
     * decide what to write, {@code SocialPreviewInitListener} to decide what
     * to leave alone. Deliberately duplicates {@code LandingPage.forPathInfo}'s
     * normalisation rather than sharing it: the two classes' path-parsing logic
     * has drifted apart once already (see that method's javadoc) precisely
     * because it lived in more than one place, so a change here should be
     * made by eye against that method, not assumed to stay in sync silently.
     *
     * @param aPathInfo the request path info, may be {@code null}
     * @return the addressed page, or {@code null}
     */
    /** Every language this edition exists in, in insertion order, English first.
     *  Used to emit the hreflang cluster: a translated page that does not tell
     *  search engines about its siblings is a page they will treat as a
     *  duplicate rather than an alternate, which wastes the translation. */
    public static List<EditionInfo> variantsOf(final String anAbbr) {
        final List<EditionInfo> out = new ArrayList<>();
        for (final EditionInfo p : PAGES.values()) {
            if (p.abbr.equals(anAbbr)) out.add(p);
        }
        return out;
    }

    /**
     * The path segment(s) after {@link #PREFIX} for this edition's info page in
     * {@code aLang} — that language's own entry when one exists, else the
     * English one.
     *
     * <p>The fallback is the whole point. Translations land one edition at a
     * time, so for most of the filling-in period a locale will have entries for
     * some editions and not others; a caller that built the path itself would
     * have to choose between linking every card to English (translations never
     * reached) or linking to a slug that may not exist (a 404 from the grid).
     * Callers should route through here rather than concatenating, so that
     * choice lives in one place — see {@code AboutView#infoCorner}.
     *
     * @param anAbbr the edition abbreviation, e.g. "WLC"
     * @param aLang  the BCP-47 language to prefer, e.g. "fi"; null or "en"
     *               asks for English
     * @return the map key to link to, or {@code null} if {@code anAbbr} is null
     */
    public static String slugFor(final String anAbbr, final String aLang) {
        if (anAbbr == null) return null;
        if (aLang != null && !aLang.isBlank() && !"en".equals(aLang)) {
            final EditionInfo translated = PAGES.get(aLang + "/" + anAbbr);
            if (translated != null) return translated.slug;
        }
        return anAbbr;
    }

    /**
     * Where the language switcher should send a reader who picks
     * {@code aLocale} while on this edition's info page.
     *
     * <p>This page is the one place a language switch is not simply a change of
     * session locale. Everywhere else the body comes from the message bundles,
     * so the session settles it; here the ARTICLE is a separate entry addressed
     * by its own path, and an explicit {@code /edition/fi/AGR1548} keeps serving
     * the Finnish one whatever the session says. Left to guess from the URL, the
     * switcher matched only a TRAILING language segment, missed the one in the
     * middle, and fell back to {@code ?lang=} — which moved the chrome and the
     * text direction and left the article Finnish for all thirteen choices.
     *
     * <p>Lives here rather than in the view because the judgement it needs is
     * this class's: {@link #slugFor} falls back to the English slug for a
     * language whose page was never written, so the switcher can offer every
     * language without ever pointing at a 404. A browser cannot make that call —
     * it has no idea which pages exist — so the destination is computed on the
     * server and handed over finished.
     *
     * @param anAbbr  the edition abbreviation, e.g. "AGR1548"
     * @param aLocale the language the reader picked
     * @return a root-relative path for the reload
     */
    public static String targetPathFor(final String anAbbr, final Locale aLocale) {
        final String lang = aLocale.getLanguage();
        final String slug = slugFor(anAbbr, lang);
        final String path = PREFIX + slug;
        // A slug with no language segment IS the English entry. Reached with a
        // non-English choice, that means the fallback fired — this language has
        // no page for this edition — so the choice has to survive in the query
        // string, or the chrome would revert to English too and the reader would
        // be told nothing about what happened.
        // toLanguageTag(), not getLanguage(): the latter is "iw" for Hebrew.
        return slug.equals(anAbbr) && !"en".equals(lang)
            ? path + "?lang=" + aLocale.toLanguageTag()
            : path;
    }

    /** One run of a facts-strip value: literal text, or text that links to
     *  another edition. {@code href} is null on a literal run. */
    public record FactPart(String text, String href) { }

    /** {@code [[ABBR|display text]]} inside a facts-strip value. The display
     *  text is the entry's own prose, so it stays in the page's language and
     *  keeps whatever case ending that language puts on it. */
    private static final Pattern FACT_LINK =
        Pattern.compile("\\[\\[([A-Za-z0-9-]+)\\|([^\\]]+)\\]\\]");

    /**
     * Split a facts-strip value into literal and linked runs, so the view can
     * render one {@code Anchor} per marked-up edition name without knowing the
     * markup.
     *
     * <p>Only names that resolve to something a reader can actually open are
     * marked up in the entries. An edition the corpus does not hold — Agricola
     * 1548, the Florinus revision, Kirkkoraamattu 1992 — is left as plain prose
     * rather than pointed at a page that would 404.
     *
     * @param aValue the raw fact value, possibly containing {@code [[…|…]]}
     * @param aLang  the language of the page being rendered, so links stay in it
     * @return the runs in order; a value with no markup yields one literal run
     */
    public static List<FactPart> parseFact(final String aValue, final String aLang) {
        final List<FactPart> out = new ArrayList<>();
        if (aValue == null || aValue.isEmpty()) return out;
        final Matcher m = FACT_LINK.matcher(aValue);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.add(new FactPart(aValue.substring(at, m.start()), null));
            out.add(new FactPart(m.group(2), hrefFor(m.group(1), aLang)));
            at = m.end();
        }
        if (at < aValue.length()) out.add(new FactPart(aValue.substring(at), null));
        return out;
    }

    /**
     * Where a marked-up edition name points: its info page in this language when
     * one exists, the English page when only that does (via {@link #slugFor}),
     * and the reader otherwise.
     *
     * <p>The reader fallback is what makes the markup safe to use ahead of the
     * writing. An edition can be in the corpus and have a card in
     * {@code AboutView} without having an entry here yet — LUT1912 is exactly
     * that today — and sending someone to the text itself is better than
     * refusing to link a name the corpus demonstrably holds. When that page is
     * written the link silently upgrades, with no change at the use site.
     *
     * @param anAbbr the edition abbreviation from the markup
     * @param aLang  the language of the page being rendered
     * @return an info-page path, or a reader deep link
     */
    private static String hrefFor(final String anAbbr, final String aLang) {
        final String slug = slugFor(anAbbr, aLang);
        if (PAGES.containsKey(slug)) return PREFIX + slug;
        return "/reader?c1.src=" + anAbbr.toLowerCase(Locale.ROOT);
    }

    public static EditionInfo forPathInfo(final String aPathInfo) {
        if (aPathInfo == null || aPathInfo.isBlank()) return null;
        String p = aPathInfo.trim();
        final int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.startsWith(PREFIX)) return null;
        // The remainder is the map key verbatim — "WLC" or "fi/WLC" — so adding
        // a language is adding an entry, not teaching this method a new shape.
        return PAGES.get(p.substring(PREFIX.length()));
    }
}
