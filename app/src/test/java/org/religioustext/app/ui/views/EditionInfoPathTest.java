package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /edition/:abbr} path resolution and the language dimension.
 *
 *  Both SEO listeners route on {@link EditionInfo#forPathInfo} — one to decide
 *  what to write, the other to decide what to leave alone — so a path either
 *  class resolves differently is a page whose head nobody writes. That exact
 *  hole existed once for {@code /read/hi/}, which is why these cases are
 *  pinned rather than assumed.
 */
class EditionInfoPathTest {

    @Test
    void englishPagesHaveNoLanguageSegment() {
        final EditionInfo wlc = EditionInfo.forPathInfo("/edition/WLC");
        assertThat(wlc).isNotNull();
        assertThat(wlc.lang).isEqualTo("en");
        assertThat(wlc.slug).isEqualTo("WLC");
    }

    /** The published URLs must keep working: English deliberately has no
     *  language segment, so no redirects are owed. */
    @Test
    void trailingSlashAndQueryAreIgnored() {
        assertThat(EditionInfo.forPathInfo("/edition/WLC/")).isNotNull();
        assertThat(EditionInfo.forPathInfo("/edition/WLC/?lang=fi")).isNotNull();
        assertThat(EditionInfo.forPathInfo("edition/WLC")).isNotNull();
    }

    @Test
    void unknownAndForeignPathsResolveToNothing() {
        assertThat(EditionInfo.forPathInfo("/edition/NOPE")).isNull();
        assertThat(EditionInfo.forPathInfo("/edition/")).isNull();
        assertThat(EditionInfo.forPathInfo("/edition")).isNull();
        assertThat(EditionInfo.forPathInfo("/read/hi")).isNull();
        assertThat(EditionInfo.forPathInfo("/")).isNull();
        assertThat(EditionInfo.forPathInfo(null)).isNull();
        assertThat(EditionInfo.forPathInfo("  ")).isNull();
    }

    /** A translation lives at /edition/<lang>/<abbr>, and the map key is that
     *  remainder verbatim — so this passes as soon as a translated entry is
     *  added, with no change to forPathInfo. Until then it asserts the shape
     *  the slug builder produces. */
    @Test
    void slugCarriesTheLanguageSegment() {
        for (final EditionInfo p : EditionInfo.PAGES.values()) {
            if ("en".equals(p.lang)) {
                assertThat(p.slug).isEqualTo(p.abbr);
            } else {
                assertThat(p.slug).isEqualTo(p.lang + "/" + p.abbr);
                assertThat(EditionInfo.forPathInfo("/edition/" + p.slug)).isSameAs(p);
            }
        }
    }

    /** Every key must be reachable by its own path — a page in the map that no
     *  URL resolves to is a page that exists only in the sitemap. */
    @Test
    void everyEntryIsReachableAtItsOwnSlug() {
        assertThat(EditionInfo.PAGES).isNotEmpty();
        EditionInfo.PAGES.forEach((key, p) -> {
            assertThat(p.slug).as("key matches slug").isEqualTo(key);
            assertThat(EditionInfo.forPathInfo("/edition/" + key)).isSameAs(p);
        });
    }

    @Test
    void variantsOfGroupsAnEditionsLanguages() {
        final List<EditionInfo> wlc = EditionInfo.variantsOf("WLC");
        assertThat(wlc).isNotEmpty();
        assertThat(wlc).allSatisfy(v -> assertThat(v.abbr).isEqualTo("WLC"));
        assertThat(EditionInfo.variantsOf("NOPE")).isEmpty();
    }

    /** LandingPage and EditionInfo must claim disjoint paths. */
    @Test
    void theTwoPageFamiliesDoNotOverlap() {
        EditionInfo.PAGES.keySet().forEach(key ->
            assertThat(LandingPage.forPathInfo("/edition/" + key)).isNull());
        LandingPage.PAGES.keySet().forEach(slug ->
            assertThat(EditionInfo.forPathInfo("/read/" + slug)).isNull());
    }
}
