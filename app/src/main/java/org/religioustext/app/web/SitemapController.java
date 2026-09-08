package org.religioustext.app.web;

import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.ui.views.EditionInfo;
import org.religioustext.app.ui.views.LandingPage;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Locale;

/**
 * Serves {@code /sitemap.xml}, generated from the same registries the router
 * uses: {@link LandingPage#PAGES} and {@link EditionInfo#PAGES}.
 *
 * <p>This replaced a hand-maintained static file whose header asked everyone
 * adding a page to "add its row here too". That stayed true only by
 * discipline, and the edition-page family grows constantly. Same reasoning as
 * {@link CorpusDownloadController}'s licence gate: a hand-maintained list is a
 * drift bug waiting for someone to add an entry and forget. Generating from
 * the registries means a page is in the sitemap exactly when it is routable,
 * and can never list a URL that 404s.
 *
 * <p>Kept deliberately small and honest (as the static file was): {@code /},
 * {@code /reader}, their language-path variants ({@code /fi},
 * {@code /reader/he}, ...), the {@code /read/:code} landing pages and the
 * {@code /edition/:abbr} pages are the only routes with distinct, real content
 * and their own title/description. {@code ?lang=} variants are NOT listed —
 * same route behind a query parameter; Google canonicalises those itself.
 * {@code /en} and {@code /reader/en} are also not listed: they canonicalise
 * to {@code /} and {@code /reader} (see SocialPreviewInitListener), so a
 * sitemap row would advertise a URL that immediately defers elsewhere.
 *
 * <p>The language variants of {@code /} and {@code /reader} carry an
 * {@code xhtml:link} hreflang cluster naming every language edition plus
 * {@code x-default} (the English base page). That marks them to Google as
 * translations of one page — each served to searchers in its language —
 * rather than competing pages, and it derives from {@link LocaleUtil#LOCALES}
 * like the routes themselves, so a new locale joins the sitemap on its own.
 */
@RestController
public class SitemapController {

    private static final String ORIGIN = "https://common-root.org";

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        // The registries are populated in static initialisers and never change
        // at runtime, but building the document costs microseconds — caching
        // headers for the crawler are all the caching this needs.
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
            .body(build());
    }

    /**
     * The complete sitemap document. Package-private and static so it can be
     * unit-tested without standing up Spring MVC — same approach as
     * {@code SecurityConfig.logoutTarget} and
     * {@code SocialPreviewInitListener.applyTo}.
     *
     * @return sitemap XML listing every registered indexable page
     */
    static String build() {
        final StringBuilder xml = new StringBuilder(16 * 1024);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
           .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n")
           .append("        xmlns:xhtml=\"http://www.w3.org/1999/xhtml\">\n");

        url(xml, "/", "weekly", "1.0", alternatesFor("/"));
        for (final Locale locale : LocaleUtil.LOCALES) {
            final String lang = locale.toLanguageTag();
            if ("en".equals(lang)) continue;   // /en canonicalises to /
            url(xml, "/" + lang, "weekly", "0.8", alternatesFor("/"));
        }

        url(xml, "/reader", "weekly", "0.9", alternatesFor("/reader"));
        for (final Locale locale : LocaleUtil.LOCALES) {
            final String lang = locale.toLanguageTag();
            if ("en".equals(lang)) continue;   // /reader/en canonicalises to /reader
            url(xml, "/reader/" + lang, "weekly", "0.7", alternatesFor("/reader"));
        }

        for (final String code : LandingPage.PAGES.keySet()) {
            url(xml, LandingPage.PREFIX + code, "monthly", "0.8", null);
        }

        for (final String abbr : EditionInfo.PAGES.keySet()) {
            // Language variants ("fi/WLC") rank below the base page ("WLC"),
            // mirroring the weights the hand-maintained file used.
            final boolean variant = abbr.indexOf('/') >= 0;
            url(xml, "/edition/" + abbr, "monthly", variant ? "0.5" : "0.6", null);
        }

        return xml.append("</urlset>\n").toString();
    }

    private static void url(final StringBuilder aXml, final String aPath,
                            final String aChangefreq, final String aPriority,
                            final String aAlternates) {
        aXml.append("  <url>\n")
            .append("    <loc>").append(ORIGIN).append(aPath).append("</loc>\n")
            .append("    <changefreq>").append(aChangefreq).append("</changefreq>\n")
            .append("    <priority>").append(aPriority).append("</priority>\n");
        if (aAlternates != null) aXml.append(aAlternates);
        aXml.append("  </url>\n");
    }

    /**
     * The {@code xhtml:link} hreflang cluster for one page family: an
     * alternate per language edition plus {@code x-default} pointing at the
     * English base page. Identical on every member of the family — hreflang
     * annotations must be reciprocal, and repeating the full cluster is the
     * documented way to satisfy that.
     *
     * @param aBase "/" or "/reader"
     * @return the alternate-link XML block
     */
    private static String alternatesFor(final String aBase) {
        final String baseUrl = ORIGIN + aBase;
        final StringBuilder x = new StringBuilder(2 * 1024);
        alternate(x, "x-default", baseUrl);
        alternate(x, "en", baseUrl);
        for (final Locale locale : LocaleUtil.LOCALES) {
            final String lang = locale.toLanguageTag();
            if ("en".equals(lang)) continue;
            alternate(x, lang,
                "/".equals(aBase) ? ORIGIN + "/" + lang : baseUrl + "/" + lang);
        }
        return x.toString();
    }

    private static void alternate(final StringBuilder aXml, final String aLang,
                                  final String aHref) {
        aXml.append("    <xhtml:link rel=\"alternate\" hreflang=\"")
            .append(aLang).append("\" href=\"").append(aHref).append("\"/>\n");
    }
}
