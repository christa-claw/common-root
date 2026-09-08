package org.religioustext.app.config;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.religioustext.app.ui.views.LandingPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.religioustext.app.config.LandingSeoListener.appendCrawlableContent;

/**
 * The crawlable body. Vaadin Flow serves an app shell with an empty {@code <body>};
 * a crawler that does not execute JavaScript sees a title, a meta description and
 * nothing else. Baidu is such a crawler in practice — its one visit in the Caddy log
 * (2026-06-25) aborted while fetching the Flow import bundle — so the landing pages
 * carry their prose in a {@code <noscript>} block that any crawler can read.
 */
class LandingSeoListenerTest {

    private static Document render(final String aSlug) {
        final Document doc = Document.createShell("");
        appendCrawlableContent(doc, LandingPage.PAGES.get(aSlug));
        return doc;
    }

    @Test
    void theProseIsInTheServedHtmlNotOnlyInTheClientBundle() {
        final Document doc = render("hi");
        final String noscript = doc.body().select("noscript").text();

        assertThat(noscript).isNotBlank();
        assertThat(noscript).contains("Indian Revised Version");
    }

    @Test
    void theHeroHeadingIsTheH1() {
        assertThat(render("quran").body().select("noscript h1").text())
            .isEqualTo(LandingPage.PAGES.get("quran").heroHeading);
    }

    @Test
    void paragraphBreaksInTheHeroBodyBecomeSeparateParagraphs() {
        // heroBody carries "\n\n" breaks; a crawler reading one undivided blob of text
        // gets worse structure than the human reader does.
        assertThat(render("he").body().select("noscript p")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void theScriptSampleAndItsGlossAreBothPresent() {
        final LandingPage page = LandingPage.PAGES.get("he");
        final Document doc = render("he");

        assertThat(doc.body().select("noscript blockquote p").text()).isEqualTo(page.scriptSample);
        assertThat(doc.body().select("noscript blockquote cite").text())
            .isEqualTo(page.scriptSampleGloss);
    }

    @Test
    void thePageLinksIntoTheReaderSoTheLinkGraphSurvivesWithoutJs() {
        assertThat(render("hi").body().select("noscript a").attr("href"))
            .isEqualTo(LandingPage.PAGES.get("hi").readerHref);
    }

    @Test
    void everyLandingPageRendersSomethingCrawlable() {
        // A page added to PAGES without prose would ship as an empty shell to Baidu
        // and never be noticed, because it looks fine in a browser.
        for (final String slug : LandingPage.PAGES.keySet()) {
            assertThat(render(slug).body().select("noscript").text())
                .describedAs("crawlable body for /read/%s", slug)
                .isNotBlank();
        }
    }
}
