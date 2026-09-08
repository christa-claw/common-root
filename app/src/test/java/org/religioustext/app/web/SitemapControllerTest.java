package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.ui.views.EditionInfo;
import org.religioustext.app.ui.views.LandingPage;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sitemap is generated from {@link LandingPage#PAGES} and
 * {@link EditionInfo#PAGES}, so these tests pin the CONTRACT — every
 * registered page listed, nothing else, correct weights — rather than a
 * snapshot of today's page set, which is the maintenance trap the generated
 * sitemap exists to remove.
 */
class SitemapControllerTest {

    private static final String XML = SitemapController.build();

    // ── completeness ─────────────────────────────────────────────────

    @Test
    void listsTheTwoFixedRoutes() {
        assertThat(XML).contains("<loc>https://common-root.org/</loc>");
        assertThat(XML).contains("<loc>https://common-root.org/reader</loc>");
    }

    @Test
    void listsEveryLandingPage() {
        for (final String code : LandingPage.PAGES.keySet()) {
            assertThat(XML)
                .contains("<loc>https://common-root.org/read/" + code + "</loc>");
        }
    }

    @Test
    void listsEveryEditionPage() {
        for (final String abbr : EditionInfo.PAGES.keySet()) {
            assertThat(XML)
                .contains("<loc>https://common-root.org/edition/" + abbr + "</loc>");
        }
    }

    @Test
    void listsNothingElse() {
        final Matcher m = Pattern.compile("<loc>([^<]+)</loc>").matcher(XML);
        int count = 0;
        while (m.find()) count++;
        // / and /reader, plus a language variant of each per non-English locale.
        final int fixed = 2 * LocaleUtil.LOCALES.size();
        assertThat(count)
            .isEqualTo(fixed + LandingPage.PAGES.size() + EditionInfo.PAGES.size());
    }

    @Test
    void listsEveryNonEnglishLanguageVariantOfTheFixedRoutes() {
        for (final var locale : LocaleUtil.LOCALES) {
            final String lang = locale.toLanguageTag();
            if ("en".equals(lang)) continue;
            assertThat(XML).contains("<loc>https://common-root.org/" + lang + "</loc>");
            assertThat(XML).contains("<loc>https://common-root.org/reader/" + lang + "</loc>");
        }
    }

    @Test
    void doesNotListTheEnglishLanguagePaths() {
        // /en and /reader/en canonicalise to / and /reader; listing them would
        // advertise URLs that immediately defer elsewhere.
        assertThat(XML).doesNotContain("<loc>https://common-root.org/en</loc>");
        assertThat(XML).doesNotContain("<loc>https://common-root.org/reader/en</loc>");
    }

    @Test
    void languageVariantsCarryTheFullHreflangCluster() {
        final String fi = entryFor("/fi");
        assertThat(fi).contains(
            "hreflang=\"x-default\" href=\"https://common-root.org/\"");
        assertThat(fi).contains(
            "hreflang=\"en\" href=\"https://common-root.org/\"");
        assertThat(fi).contains(
            "hreflang=\"he\" href=\"https://common-root.org/he\"");
        // The cluster is reciprocal: the base page carries it too.
        assertThat(entryFor("/")).contains(
            "hreflang=\"fi\" href=\"https://common-root.org/fi\"");
        // Reader family points into /reader, not the root pages.
        assertThat(entryFor("/reader/fi")).contains(
            "hreflang=\"he\" href=\"https://common-root.org/reader/he\"");
    }

    @Test
    void editionPagesCarryNoHreflang() {
        assertThat(entryFor("/edition/WLC")).doesNotContain("hreflang");
    }

    // ── weights ──────────────────────────────────────────────────────

    @Test
    void languageVariantEditionsRankBelowBaseEditions() {
        // A variant key contains a slash; its entry must carry 0.5, a base 0.6.
        assertThat(entryFor("/edition/WLC")).contains("<priority>0.6</priority>");
        assertThat(entryFor("/edition/fi/WLC")).contains("<priority>0.5</priority>");
    }

    @Test
    void homepageOutranksEverything() {
        assertThat(entryFor("/")).contains("<priority>1.0</priority>");
        assertThat(entryFor("/reader")).contains("<priority>0.9</priority>");
    }

    // ── well-formedness (cheap but catches an unescaped key early) ───

    @Test
    void isParseableXml() throws Exception {
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(
                XML.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** The full {@code <url>...</url>} block whose loc is exactly {@code aPath}. */
    private static String entryFor(final String aPath) {
        final String loc = "<loc>https://common-root.org" + aPath + "</loc>";
        final int at = XML.indexOf(loc);
        assertThat(at).as("entry for %s exists", aPath).isNotNegative();
        final int start = XML.lastIndexOf("<url>", at);
        return XML.substring(start, XML.indexOf("</url>", at));
    }
}
