package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;
import org.religioustext.app.i18n.LocaleUtil;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link EditionInfo#targetPathFor} — where the language switcher sends a
 * reader who changes language while on an edition's info page.
 *
 * <p>Written against a real bug: {@code /edition/fi/AGR1548} carries its
 * language in the MIDDLE of the path, {@code LanguageSelect} rewrote only a
 * TRAILING language segment, so the switch degraded to {@code ?lang=} and the
 * article stayed Finnish in all thirteen languages while the chrome moved. The
 * cases below are the ones that made client-side URL rewriting the wrong tool:
 * a language whose page was never written, and an abbreviation that IS a
 * language code.
 */
class EditionInfoTargetPathTest {

    /** The switch this test exists for: a written translation is reached by
     *  path, so the article moves with the chrome. */
    @Test
    void sendsAWrittenTranslationToItsOwnPath() {
        assertThat(EditionInfo.targetPathFor("AGR1548", LocaleUtil.DE))
            .isEqualTo("/edition/de/AGR1548");
        assertThat(EditionInfo.targetPathFor("AGR1548", LocaleUtil.FI))
            .isEqualTo("/edition/fi/AGR1548");
    }

    /** English is the base entry and deliberately carries no language segment,
     *  so the URLs already published stay valid. */
    @Test
    void sendsEnglishToTheBareSlug() {
        assertThat(EditionInfo.targetPathFor("AGR1548", LocaleUtil.EN))
            .isEqualTo("/edition/AGR1548");
    }

    /**
     * The case a browser could never get right. An edition with an English
     * entry and no German translation falls back to the English article — a
     * naive segment swap would have built {@code /edition/de/XYZ} and 404ed.
     * The chosen language still has to reach the chrome, hence the query
     * string.
     *
     * <p>The probe edition is found, not named: this test used to say DIO, and
     * the nightly edition drafting translated DIO into German (2026-09), which
     * broke the premise, not the rule. If every edition ever gains a German
     * page the test has nothing to fall back from and is skipped, honestly.
     */
    @Test
    void fallsBackToEnglishWithoutLosingTheChoice() {
        final String untranslated = EditionInfo.PAGES.keySet().stream()
            .filter(k -> !k.contains("/") && !EditionInfo.PAGES.containsKey("de/" + k))
            .sorted().findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(untranslated != null,
            "every edition now has a German page; nothing left to fall back from");
        assertThat(EditionInfo.targetPathFor(untranslated, LocaleUtil.DE))
            .isEqualTo("/edition/" + untranslated + "?lang=de");
    }

    /**
     * {@code TR} is the Textus Receptus and {@code tr} is Turkish, so the
     * Turkish page for that edition is {@code /edition/tr/TR}. Any rule that
     * matched a language tag case-insensitively, or matched on the LAST
     * segment, would mangle exactly this URL.
     */
    @Test
    void survivesTheAbbreviationThatIsAlsoALanguageCode() {
        assertThat(EditionInfo.targetPathFor("TR", LocaleUtil.TR))
            .isEqualTo("/edition/tr/TR");
        assertThat(EditionInfo.targetPathFor("TR", LocaleUtil.EN))
            .isEqualTo("/edition/TR");
    }

    /** Hebrew is the standing trap in Java locale handling: {@code
     *  getLanguage()} answers "iw" for some constructions, and the slugs are
     *  built with "he". Whatever it answers, the two sides must agree. */
    @Test
    void agreesWithTheSlugsForHebrew() {
        final String path = EditionInfo.targetPathFor("AGR1548", LocaleUtil.HE);
        assertThat(path).isEqualTo("/edition/he/AGR1548");
        assertThat(EditionInfo.PAGES).containsKey(path.substring(EditionInfo.PREFIX.length()));
    }

    /** Every language the UI offers must resolve to a page that exists — the
     *  whole point of routing through {@link EditionInfo#slugFor}. */
    @Test
    void neverPointsAtAPageThatDoesNotExist() {
        for (final String abbr : new String[] {"AGR1548", "DIO", "TR", "DBY", "WLC"}) {
            for (final Locale locale : LocaleUtil.LOCALES) {
                final String path = EditionInfo.targetPathFor(abbr, locale);
                final String slug = path.substring(EditionInfo.PREFIX.length())
                    .replaceAll("\\?.*$", "");
                assertThat(EditionInfo.PAGES)
                    .as("%s in %s -> %s", abbr, locale.toLanguageTag(), path)
                    .containsKey(slug);
            }
        }
    }
}
