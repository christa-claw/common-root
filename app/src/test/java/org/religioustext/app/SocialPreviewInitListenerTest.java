// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.religioustext.app.ui.views.LandingPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.religioustext.app.SocialPreviewInitListener.applyTo;
import static org.religioustext.app.SocialPreviewInitListener.canonicalUrlFor;
import static org.religioustext.app.SocialPreviewInitListener.isLandingPage;
import static org.religioustext.app.SocialPreviewInitListener.isNoIndex;

/**
 * The site-wide SEO head. The regression this guards: a single hardcoded canonical
 * pointing at the homepage on EVERY route, which told Google that {@code /reader} —
 * listed in {@code sitemap.xml} at priority 0.9 — was a duplicate of "/" and should
 * not be indexed. Search Console reported it under "Alternate page with proper
 * canonical tag" and the page never appeared.
 *
 * <p>Also guards the disjoint-domain contract with {@code LandingSeoListener}: the
 * two listeners must not both write the head, because Vaadin's Spring integration
 * gives no ordering guarantee between them.
 */
class SocialPreviewInitListenerTest {

    private static final String ORIGIN = "https://common-root.org";

    // ── Canonical URLs ────────────────────────────────────────────────

    @Test
    void readerCanonicalisesToItselfNotTheHomepage() {
        // THE bug. If this ever reads ORIGIN or ORIGIN + "/", /reader is deindexed again.
        assertThat(canonicalUrlFor("/reader")).isEqualTo(ORIGIN + "/reader");
    }

    @Test
    void rootKeepsItsTrailingSlash() {
        assertThat(canonicalUrlFor("/")).isEqualTo(ORIGIN + "/");
        assertThat(canonicalUrlFor(null)).isEqualTo(ORIGIN + "/");
        assertThat(canonicalUrlFor("   ")).isEqualTo(ORIGIN + "/");
    }

    @Test
    void queryStringsAreDroppedSoLangVariantsFoldOntoTheRoute() {
        // The ?lang= variants ARE duplicates of their route — the one case the old
        // homepage-canonical behaviour got right, kept deliberately.
        assertThat(canonicalUrlFor("/?lang=fi")).isEqualTo(ORIGIN + "/");
        // English path routes are the same pages as / and /reader; canonical
        // collapses them so crawlers never see duplicate English pages.
        assertThat(canonicalUrlFor("/en")).isEqualTo(ORIGIN + "/");
        assertThat(canonicalUrlFor("/reader/en")).isEqualTo(ORIGIN + "/reader");
        // Non-English language paths remain self-canonical (translated content).
        assertThat(canonicalUrlFor("/fi")).isEqualTo(ORIGIN + "/fi");
        assertThat(canonicalUrlFor("/reader/he")).isEqualTo(ORIGIN + "/reader/he");
        assertThat(canonicalUrlFor("/reader?c1.src=niv&cols=2")).isEqualTo(ORIGIN + "/reader");
    }

    @Test
    void trailingSlashesCollapseToOneUrl() {
        assertThat(canonicalUrlFor("/reader/")).isEqualTo(ORIGIN + "/reader");
        assertThat(canonicalUrlFor("/reader///")).isEqualTo(ORIGIN + "/reader");
    }

    @Test
    void aPathWithoutALeadingSlashIsStillAbsolute() {
        assertThat(canonicalUrlFor("reader")).isEqualTo(ORIGIN + "/reader");
    }

    // ── Disjoint domains with LandingSeoListener ──────────────────────

    @Test
    void knownLandingPagesAreLeftToTheLandingListener() {
        assertThat(isLandingPage("/read/hi")).isTrue();
        assertThat(isLandingPage("/read/he")).isTrue();
        assertThat(isLandingPage("/read/quran")).isTrue();
    }

    @Test
    void unknownReadCodesAreNotLandingPages() {
        // /read/zz 404s. Claiming it here would canonicalise a page that does not exist;
        // leaving it to this listener gets it a noindex instead.
        assertThat(isLandingPage("/read/zz")).isFalse();
        assertThat(isLandingPage("/read/")).isFalse();
    }

    @Test
    void ordinaryRoutesAreNotLandingPages() {
        assertThat(isLandingPage("/")).isFalse();
        assertThat(isLandingPage("/reader")).isFalse();
        assertThat(isLandingPage(null)).isFalse();
    }

    @Test
    void landingPagesAreStillMatchedWithAQueryStringOrTrailingSlash() {
        assertThat(isLandingPage("/read/hi/")).isTrue();
        assertThat(isLandingPage("/read/hi?utm_source=hn")).isTrue();
    }

    @Test
    void theTwoListenersDomainsAreExactComplements() {
        // A path claimed by NEITHER listener gets no canonical and no preview tags at
        // all. "/read/hi/" used to be exactly that hole: this listener skipped it as a
        // landing page while LandingSeoListener's raw substring lookup resolved "hi/"
        // to nothing and skipped it too. Both now route through LandingPage.forPathInfo.
        for (final String path : new String[] {
                "/", "/reader", "/read/hi", "/read/hi/", "/read/hi?utm_source=hn",
                "/read/zz", "/read/", "/login", "/admin/acls" }) {
            assertThat(isLandingPage(path))
                .describedAs("who owns %s", path)
                .isEqualTo(LandingPage.forPathInfo(path) != null);
        }
    }

    @Test
    void aLandingPageResolvesToItsSlugRegardlessOfTrailingSlashOrQuery() {
        // LandingSeoListener builds its canonical from the slug, so these must agree.
        assertThat(LandingPage.forPathInfo("/read/hi").slug).isEqualTo("hi");
        assertThat(LandingPage.forPathInfo("/read/hi/").slug).isEqualTo("hi");
        assertThat(LandingPage.forPathInfo("/read/hi?utm_source=hn").slug).isEqualTo("hi");
    }

    // ── noindex ───────────────────────────────────────────────────────

    @Test
    void authAndAccountRoutesAreNoIndex() {
        assertThat(isNoIndex("/login")).isTrue();
        assertThat(isNoIndex("/register")).isTrue();
        assertThat(isNoIndex("/forgot")).isTrue();
        assertThat(isNoIndex("/profile")).isTrue();
        assertThat(isNoIndex("/preferences")).isTrue();
    }

    @Test
    void tokenBearingRoutesAreNoIndexEvenWithTheirTokens() {
        assertThat(isNoIndex("/reset?token=abc123")).isTrue();
        assertThat(isNoIndex("/verify?token=abc123")).isTrue();
    }

    @Test
    void adminAndItsSubpathsAreNoIndex() {
        assertThat(isNoIndex("/admin")).isTrue();
        assertThat(isNoIndex("/admin/acls")).isTrue();
    }

    @Test
    void unknownReadCodesAreNoIndex() {
        assertThat(isNoIndex("/read/zz")).isTrue();
    }

    @Test
    void thePagesWeActuallyWantIndexedAreNotNoIndex() {
        assertThat(isNoIndex("/")).isFalse();
        assertThat(isNoIndex("/reader")).isFalse();
        assertThat(isNoIndex(null)).isFalse();
    }

    @Test
    void noIndexDoesNotLeakToLookalikePaths() {
        // Guard the prefix match: /administration is not the admin tool.
        assertThat(isNoIndex("/administration")).isFalse();
        assertThat(isNoIndex("/logout-notice")).isFalse();
    }

    // ── The rendered head ─────────────────────────────────────────────

    @Test
    void readerHeadCarriesASelfCanonicalAndNoRobotsTag() {
        final Document doc = Document.createShell("");
        applyTo(doc, "/reader", null);

        assertThat(doc.head().select("link[rel=canonical]").attr("href"))
            .isEqualTo(ORIGIN + "/reader");
        assertThat(doc.head().select("meta[property=og:url]").attr("content"))
            .isEqualTo(ORIGIN + "/reader");
        assertThat(doc.head().select("meta[name=robots]")).isEmpty();
    }

    @Test
    void theHeadIsWrittenExactlyOnceSoNoTagIsAmbiguous() {
        // The duplicate-description bug: the app shell used to emit its own set on top
        // of this one, leaving two conflicting <meta name="description"> per page.
        final Document doc = Document.createShell("");
        applyTo(doc, "/reader", null);

        assertThat(doc.head().select("meta[name=description]")).hasSize(1);
        assertThat(doc.head().select("link[rel=canonical]")).hasSize(1);
        assertThat(doc.head().select("meta[property=og:title]")).hasSize(1);
        assertThat(doc.head().select("meta[name=twitter:card]")).hasSize(1);
    }

    @Test
    void openGraphTagsUsePropertyNotName() {
        // property= is what OpenGraph parsers read; name="og:..." is silently ignored.
        final Document doc = Document.createShell("");
        applyTo(doc, "/", null);

        assertThat(doc.head().select("meta[property=og:title]")).isNotEmpty();
        assertThat(doc.head().select("meta[name^=og:]")).isEmpty();
    }

    @Test
    void loginHeadCarriesNoindexFollow() {
        final Document doc = Document.createShell("");
        applyTo(doc, "/login", null);

        assertThat(doc.head().select("meta[name=robots]").attr("content"))
            .isEqualTo("noindex, follow");
    }
}
