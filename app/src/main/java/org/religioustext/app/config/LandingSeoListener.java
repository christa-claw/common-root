package org.religioustext.app.config;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.religioustext.app.ui.views.EditionInfo;
import org.religioustext.app.ui.views.LandingPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Injects a real, page-specific {@code <title>}, meta description, canonical URL
 * and OpenGraph tags into the initial HTML response for the two families of
 * curated info pages: {@code /read/:code} language landing pages (see {@link
 * org.religioustext.app.ui.views.LandingPageView}) and {@code /edition/:abbr}
 * per-edition bibliographic pages (see {@link
 * org.religioustext.app.ui.views.EditionInfoView}). Both are plain data maps
 * with their own {@code forPathInfo} resolver — {@link
 * org.religioustext.app.ui.views.LandingPage#forPathInfo} and {@link
 * org.religioustext.app.ui.views.EditionInfo#forPathInfo} — tried in that
 * order below.
 *
 * <p>Why this exists rather than setting the title from within the view: Vaadin
 * is a client-side-routed SPA, so only the FIRST request for a given browser
 * session gets a full server-rendered HTML document — that document's
 * {@code <head>} is what search engines and link-preview scrapers (Slack,
 * Discord, Twitter/X, HN) actually read. {@link
 * com.vaadin.flow.router.HasDynamicTitle}, set in the view itself, only
 * updates the LIVE DOM after JavaScript has already run, which is invisible
 * to a scraper that never executes JS at all (most link-preview bots don't).
 * {@code IndexHtmlRequestListener} is the Vaadin-native hook that runs
 * server-side, before the response is sent, so this actually lands in the
 * bytes a crawler receives.
 *
 * <h2>Ordering against SocialPreviewInitListener no longer matters</h2>
 * Both this class and {@link org.religioustext.app.SocialPreviewInitListener}
 * are unordered {@code VaadinServiceInitListener} beans, and Vaadin's Spring
 * integration collects them with {@code getBeansOfType(..)} — which does NOT
 * honour {@code @Order}. Whichever ran last used to win, so this feature worked
 * only by component-scan luck; a reorder would silently restore the homepage
 * canonical on every landing page. Since 2026-07-28 the two listeners have
 * DISJOINT path domains instead: {@code SocialPreviewInitListener} returns
 * early for every known {@code /read/:code} page, and this one returns early
 * for everything else. Neither can clobber the other, in either order.
 *
 * <p>The {@code remove(..)} calls below are therefore belt-and-braces rather
 * than load-bearing — they cost one selector pass and mean a future app-shell
 * tag can never quietly reintroduce the duplicate-canonical bug on these pages.
 */
@Component
public class LandingSeoListener implements VaadinServiceInitListener {

    private static final Logger log = LoggerFactory.getLogger(LandingSeoListener.class);

    @Override
    public void serviceInit(final ServiceInitEvent aEvent) {
        aEvent.addIndexHtmlRequestListener(this::injectLandingTags);
    }

    private void injectLandingTags(final IndexHtmlResponse aResponse) {
        try {
            // One shared resolver per page family with SocialPreviewInitListener's
            // isLandingPage/isEditionPage — see each forPathInfo's own javadoc.
            // Anything neither claims (an unknown code, or any other route)
            // belongs to that other listener, which has already written that
            // page's head. Leave it alone.
            final String path = aResponse.getVaadinRequest().getPathInfo();
            final LandingPage readPage = LandingPage.forPathInfo(path);
            final EditionInfo editionPage = readPage == null ? EditionInfo.forPathInfo(path) : null;
            if (readPage == null && editionPage == null) return;

            final String title, description, canonicalUrl;
            if (readPage != null) {
                title = readPage.title;
                description = readPage.description;
                canonicalUrl = "https://common-root.org" + LandingPage.PREFIX + readPage.slug;
            } else {
                title = editionPage.title;
                description = editionPage.description;
                canonicalUrl = "https://common-root.org" + EditionInfo.PREFIX + editionPage.slug;
            }

            final Document doc = aResponse.getDocument();

            // Defensive cleanup — see the ordering note in the class javadoc. Matches
            // BOTH attribute forms because AppShellSettings.addMetaTag can only write
            // name="og:...", never the OpenGraph-standard property="og:...".
            doc.head().select("meta[name=description]").remove();
            doc.head().select("meta[property^=og:], meta[name^=og:]").remove();
            doc.head().select("meta[name^=twitter:]").remove();
            doc.head().select("link[rel=canonical]").remove();

            doc.title(title);
            doc.head().appendElement("link")
                .attr("rel", "canonical").attr("href", canonicalUrl);

            // hreflang cluster for a translated edition page. Emitted only when
            // the edition exists in more than one language: a lone self-
            // referencing alternate says nothing, and the tags are worse than
            // useless if they are not reciprocal. English carries x-default
            // because it is the version with no language segment in its path.
            if (editionPage != null) {
                final var variants = EditionInfo.variantsOf(editionPage.abbr);
                if (variants.size() > 1) {
                    doc.head().select("link[rel=alternate][hreflang]").remove();
                    for (final EditionInfo v : variants) {
                        final String href = "https://common-root.org" + EditionInfo.PREFIX + v.slug;
                        doc.head().appendElement("link").attr("rel", "alternate")
                            .attr("hreflang", v.lang).attr("href", href);
                        if ("en".equals(v.lang)) {
                            doc.head().appendElement("link").attr("rel", "alternate")
                                .attr("hreflang", "x-default").attr("href", href);
                        }
                    }
                }
            }
            doc.head().appendElement("meta")
                .attr("name", "description").attr("content", description);
            doc.head().appendElement("meta")
                .attr("property", "og:title").attr("content", title);
            doc.head().appendElement("meta")
                .attr("property", "og:description").attr("content", description);
            doc.head().appendElement("meta")
                .attr("property", "og:type").attr("content", "website");
            doc.head().appendElement("meta")
                .attr("property", "og:url").attr("content", canonicalUrl);
            doc.head().appendElement("meta")
                .attr("property", "og:image").attr("content", "https://common-root.org/images/og-cover.png");
            doc.head().appendElement("meta")
                .attr("name", "twitter:card").attr("content", "summary_large_image");
            doc.head().appendElement("meta")
                .attr("name", "twitter:title").attr("content", title);
            doc.head().appendElement("meta")
                .attr("name", "twitter:description").attr("content", description);
            doc.head().appendElement("meta")
                .attr("name", "twitter:image").attr("content", "https://common-root.org/images/og-cover.png");

            if (readPage != null) appendCrawlableContent(doc, readPage);
            else appendCrawlableContent(doc, editionPage);
        } catch (final RuntimeException ex) {
            // Never let a bootstrap-page tag failure break the actual page load —
            // worst case a landing page falls back to the generic app-wide tags.
            // Deliberately catches the WHOLE method body, including the initial
            // getPathInfo()/getVaadinRequest() calls: this runs on the bootstrap
            // path for EVERY request (not just /read/* or /edition/*), so an
            // unexpected null or an unusual request type here must never be able
            // to take the app down.
            log.warn("LandingSeoListener: failed to process index.html request: {}", ex.toString());
        }
    }

    /**
     * Write the page's prose into the bootstrap {@code <body>} inside a
     * {@code <noscript>}, so a crawler that does not execute JavaScript has
     * something to read.
     *
     * <h2>Why this is needed at all</h2>
     * Vaadin Flow serves an app shell: the {@code <head>} above is rendered
     * server-side, but the {@code <body>} is empty until the client bundle runs
     * and mounts the view. Google renders JavaScript and copes. Baidu largely does
     * not — and the one {@code Baiduspider-render/2.0} visit in the Caddy log
     * (2026-06-25) aborted mid-way through fetching the Flow import bundle, so it
     * almost certainly never rendered anything. Without this block a landing page
     * reaches such a crawler as a title and a meta description over an empty body.
     *
     * <h2>Why {@code <noscript>} rather than real body content</h2>
     * Content placed directly in the body would either survive Vaadin's bootstrap
     * as visible duplicate text or flash and vanish, depending on how the client
     * mounts into the outlet — a behaviour that has to be watched in a real
     * browser, not reasoned about. {@code <noscript>} is in the served HTML for
     * any crawler to read, is never shown to a user whose JS works, and cannot
     * interfere with Vaadin's DOM. It is the conservative half of the fix; moving
     * this prose into a first-paint skeleton inside the outlet would carry more
     * SEO weight and should be tried, but only with a browser open.
     *
     * <p>This is NOT cloaking: the text here is the same prose
     * {@code LandingPageView} renders for a human on the same URL.
     *
     * @param aDoc  the bootstrap document being assembled
     * @param aPage the landing page whose content to write
     */
    static void appendCrawlableContent(final Document aDoc, final LandingPage aPage) {
        final Element ns = aDoc.body().appendElement("noscript");
        ns.appendElement("h1").text(aPage.heroHeading);
        for (final String para : aPage.heroBody.split("\n\n")) {
            if (!para.isBlank()) ns.appendElement("p").text(para.trim());
        }
        final Element quote = ns.appendElement("blockquote");
        quote.appendElement("p").text(aPage.scriptSample);
        quote.appendElement("cite").text(aPage.scriptSampleGloss);
        // An internal link, so the page contributes to the link graph a non-rendering
        // crawler can actually see.
        ns.appendElement("a").attr("href", aPage.readerHref).text(aPage.heroHeading);
    }

    /** Same rationale as {@link #appendCrawlableContent(Document, LandingPage)},
     *  for the {@code /edition/:abbr} page family — written as the facts strip
     *  plus the narrative, since these pages have no script-sample pull-quote. */
    static void appendCrawlableContent(final Document aDoc, final EditionInfo aPage) {
        final Element ns = aDoc.body().appendElement("noscript");
        ns.appendElement("h1").text(aPage.heroHeading);
        for (final String para : aPage.heroBody.split("\n\n")) {
            if (!para.isBlank()) ns.appendElement("p").text(para.trim());
        }
        final Element facts = ns.appendElement("dl");
        for (final var entry : aPage.facts.entrySet()) {
            facts.appendElement("dt").text(entry.getKey());
            facts.appendElement("dd").text(entry.getValue());
        }
        ns.appendElement("a").attr("href", aPage.readerHref).text(aPage.heroHeading);
    }
}
