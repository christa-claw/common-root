// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.ui.views.EditionInfo;
import org.religioustext.app.ui.views.LandingPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Injects the site-wide SEO head: canonical URL, meta description, OpenGraph and
 * Twitter Card tags, into the Vaadin bootstrap HTML for every route EXCEPT the
 * {@code /read/:code} and {@code /edition/:abbr} curated info pages, which
 * {@link org.religioustext.app.config.LandingSeoListener} owns.
 *
 * <p>LANGUAGE-SPECIFIC PREVIEWS (2026-08-28)
 * Supports BOTH URL formats for language selection:
 * - Path-based: {@code /hi/}, {@code /ar/}, {@code /he/} (new, SEO-friendly)
 * - Query param: {@code /?lang=hi}, {@code /?lang=ar} (legacy, still supported)
 *
 * Both formats land on the same page with identical query string handling, but
 * generate language-specific meta tags so link previews show localized titles
 * and descriptions. A missing language defaults to English.
 */
@Component
public class SocialPreviewInitListener implements VaadinServiceInitListener {

    private static final Logger log = LoggerFactory.getLogger(SocialPreviewInitListener.class);
    static final String ORIGIN = "https://common-root.org";
    private static final String LANDING_PREFIX = LandingPage.PREFIX;
    private static final String EDITION_PREFIX = EditionInfo.PREFIX;
    private static final String SITE_NAME = "Common Root?";
    private static final String TITLE_DEFAULT =
            "Common Root? — the Abrahamic scriptures, side by side";
    private static final String DESCRIPTION_DEFAULT =
            "Read the Bible, Qur'an, and Hadith in parallel columns, with sourced "
                    + "commentary anchored verse by verse.";
    private static final String IMAGE = ORIGIN + "/images/og-cover.png";

    private static final Map<String, LanguageMeta> LANGUAGE_META = Map.ofEntries(
            Map.entry("en", new LanguageMeta(
                    "Common Root? — the Abrahamic scriptures, side by side",
                    "Read the Bible, Qur'an, and Hadith in parallel columns, with sourced commentary."
            )),
            Map.entry("hi", new LanguageMeta(
                    "Common Root? — अब्राहमिक धर्मग्रंथ, एक साथ पढ़ें",
                    "बाइबिल, कुरान और हदीस को समानांतर स्तंभों में पढ़ें, स्रोत संकेत के साथ।"
            )),
            Map.entry("ar", new LanguageMeta(
                    "Common Root? — الكتاب المقدس والقرآن والحديث، جنباً إلى جنب",
                    "اقرأ الكتاب المقدس والقرآن والحديث في أعمدة متوازية مع التعليقات المصدر."
            )),
            Map.entry("he", new LanguageMeta(
                    "Common Root? — הכתוב הקדוש, הקוראן וההדית, זה לצד זה",
                    "קרא את הכתוב הקדוש, הקוראן וההדית בעמודות מקבילות עם הערות ממקורות."
            )),
            Map.entry("es", new LanguageMeta(
                    "Common Root? — las Escrituras Abrahámicas, lado a lado",
                    "Lee la Biblia, el Corán y el Hadiz en columnas paralelas con comentarios de fuentes."
            )),
            Map.entry("fr", new LanguageMeta(
                    "Common Root? — les Écritures Abrahámiques, côte à côte",
                    "Lisez la Bible, le Coran et le Hadith en colonnes parallèles avec des commentaires sourcés."
            )),
            Map.entry("de", new LanguageMeta(
                    "Common Root? — die abrahamitischen Schriften, Seite an Seite",
                    "Lesen Sie Bibel, Koran und Hadith in parallelen Spalten mit kommentierten Quellen."
            )),
            Map.entry("fi", new LanguageMeta(
                    "Common Root? — abrahamilaiset pyhät kirjoitukset rinnakkain",
                    "Lue Raamattua, Koraania ja hadithia rinnakkaisissa sarakkeissa lähteistetyin kommentein."
            )),
            Map.entry("sv", new LanguageMeta(
                    "Common Root? — de abrahamitiska skrifterna, sida vid sida",
                    "Läs Bibeln, Koranen och hadith i parallella kolumner med källhänvisade kommentarer."
            )),
            Map.entry("ru", new LanguageMeta(
                    "Common Root? — авраамические писания бок о бок",
                    "Читайте Библию, Коран и хадисы в параллельных колонках с комментариями по источникам."
            )),
            Map.entry("zh", new LanguageMeta(
                    "Common Root? — 亚伯拉罕诸经并列对照",
                    "在平行栏目中阅读圣经、古兰经与圣训，附有出处注释。"
            )),
            Map.entry("it", new LanguageMeta(
                    "Common Root? — le Scritture abramitiche, fianco a fianco",
                    "Leggi la Bibbia, il Corano e gli Hadith in colonne parallele con commenti documentati."
            )),
            Map.entry("tr", new LanguageMeta(
                    "Common Root? — İbrahimî kutsal metinler yan yana",
                    "Kitab-ı Mukaddes'i, Kur'an'ı ve hadisleri kaynaklı açıklamalarla paralel sütunlarda okuyun."
            ))
    );

    private static final Set<String> NOINDEX_PATHS = Set.of(
            "/login", "/register", "/forgot", "/reset", "/verify",
            "/profile", "/preferences");
    private static final String ADMIN_PREFIX = "/admin";

    @Override
    public void serviceInit(final ServiceInitEvent anEvent) {
        anEvent.addIndexHtmlRequestListener(this::inject);
    }

    private void inject(final IndexHtmlResponse aResponse) {
        try {
            final String pathInfo = aResponse.getVaadinRequest().getPathInfo();
            // getPathInfo() never carries the query string; ask for the one
            // parameter the language logic cares about instead.
            final String lang = aResponse.getVaadinRequest().getParameter("lang");
            final String queryString = (lang == null) ? null : "lang=" + lang;
            if (isLandingPage(pathInfo) || isEditionPage(pathInfo)) return;
            applyTo(aResponse.getDocument(), pathInfo, queryString);
        } catch (final RuntimeException ex) {
            log.warn("SocialPreviewInitListener: failed to process index.html request: {}", ex.toString());
        }
    }

    static void applyTo(final Document aDoc, final String aPath, final String aQueryString) {
        final Element head = aDoc.head();
        final String language = extractLanguage(aPath, aQueryString);
        final LanguageMeta langMeta = LANGUAGE_META.getOrDefault(language,
                new LanguageMeta(TITLE_DEFAULT, DESCRIPTION_DEFAULT));
        final String url = canonicalUrlFor(aPath);

        // Crawlers read the bootstrap HTML's lang attribute; keep it in step
        // with the detected language (LocaleInitListener keeps it live after
        // client-side switches).
        aDoc.selectFirst("html").attr("lang", language);

        named(head, "description", langMeta.description);
        head.appendElement("link").attr("rel", "canonical").attr("href", url);
        if (isNoIndex(aPath)) named(head, "robots", "noindex, follow");

        property(head, "og:site_name", SITE_NAME);
        property(head, "og:type", "website");
        property(head, "og:title", langMeta.title);
        property(head, "og:description", langMeta.description);
        property(head, "og:url", url);
        property(head, "og:image", IMAGE);
        property(head, "og:image:width", "1200");
        property(head, "og:image:height", "630");
        property(head, "og:image:alt", SITE_NAME);

        named(head, "twitter:card", "summary_large_image");
        named(head, "twitter:title", langMeta.title);
        named(head, "twitter:description", langMeta.description);
        named(head, "twitter:image", IMAGE);
    }

    static String extractLanguage(final String aPath, final String aQueryString) {
        final String normalizedPath = normalise(aPath);
        Optional<String> pathLang = extractLanguageFromPath(normalizedPath);
        if (pathLang.isPresent()) return pathLang.get();
        if (aQueryString != null && !aQueryString.isBlank()) {
            Optional<String> queryLang = extractLanguageFromQuery(aQueryString);
            if (queryLang.isPresent()) return queryLang.get();
        }
        return "en";
    }

    private static Optional<String> extractLanguageFromPath(final String aPath) {
        if (aPath == null || aPath.equals("/")) return Optional.empty();
        String p = aPath;
        if (p.startsWith("/")) p = p.substring(1);
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return Optional.empty();
        final String[] segments = p.split("/");
        if (segments.length == 0) return Optional.empty();
        final String lastSegment = segments[segments.length - 1];
        final Locale loc = LocaleUtil.fromTag(lastSegment);
        return loc != null ? Optional.of(loc.toLanguageTag()) : Optional.empty();
    }

    private static Optional<String> extractLanguageFromQuery(final String aQueryString) {
        if (aQueryString == null || aQueryString.isBlank()) return Optional.empty();
        final String[] params = aQueryString.split("&");
        for (final String param : params) {
            if (param.startsWith("lang=")) {
                final String value = param.substring(5).trim();
                final Locale loc = LocaleUtil.fromTag(value);
                if (loc != null) {
                    return Optional.of(loc.toLanguageTag());
                }
            }
        }
        return Optional.empty();
    }

    static boolean isLandingPage(final String aPath) {
        return LandingPage.forPathInfo(aPath) != null;
    }

    static boolean isEditionPage(final String aPath) {
        return EditionInfo.forPathInfo(aPath) != null;
    }

    static String canonicalUrlFor(final String aPath) {
        final String p = normalise(aPath);
        // /en and /reader/en are the same English pages as / and /reader —
        // the route exists for URL consistency, but presenting it to crawlers
        // as a separate self-canonical page would split ranking between
        // duplicates. Non-English language paths stay self-canonical: they
        // carry genuinely different (translated) content.
        if ("/en".equals(p)) return ORIGIN + "/";
        if ("/reader/en".equals(p)) return ORIGIN + "/reader";
        return "/".equals(p) ? ORIGIN + "/" : ORIGIN + p;
    }

    static boolean isNoIndex(final String aPath) {
        final String p = normalise(aPath);
        if (NOINDEX_PATHS.contains(p)) return true;
        if (p.equals(ADMIN_PREFIX) || p.startsWith(ADMIN_PREFIX + "/")) return true;
        return p.startsWith(LANDING_PREFIX) || p.startsWith(EDITION_PREFIX);
    }

    private static String normalise(final String aPath) {
        if (aPath == null || aPath.isBlank()) return "/";
        String p = aPath.trim();
        final int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p.isEmpty() ? "/" : p;
    }

    private static void property(final Element aHead, final String aKey, final String aContent) {
        aHead.appendElement("meta").attr("property", aKey).attr("content", aContent);
    }

    private static void named(final Element aHead, final String aKey, final String aContent) {
        aHead.appendElement("meta").attr("name", aKey).attr("content", aContent);
    }

    static class LanguageMeta {
        final String title;
        final String description;
        LanguageMeta(final String aTitle, final String aDescription) {
            this.title = aTitle;
            this.description = aDescription;
        }
    }
}
