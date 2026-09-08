package org.religioustext.app.ui.views.reader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link SourceRow}. These pin the decoding that was
 * previously inlined as {@code row[i]} / {@code r.length > i ? r[i] : …} across
 * ReaderView, so the extracted accessor is provably behaviour-identical.
 */
class SourceRowTest {

    // A full 9-field Bible row (blank baseText -> a primary source).
    private static String[] bible() {
        return new String[]{"bible-niv", "New International Version (English)", "niv", "ltr",
                            "\u00A9 Biblica", "https://biblica.com", "bible", "en", ""};
    }
    // Arabic Qur'an base (rtl, blank baseText -> a primary source).
    private static String[] quranBase() {
        return new String[]{"quran-ar", "Qur'an (Arabic)", "q-ar", "rtl",
                            "Public Domain", "tanzil.net", "quran", "ar", ""};
    }
    // Qur'an translation (baseText points at the Arabic base -> a companion translation).
    private static String[] quranTranslation() {
        return new String[]{"quran-en-sahih", "Sahih International", "q-en", "ltr",
                            "Public Domain", "tanzil.net", "quran", "en", "quran-ar"};
    }

    @Test
    void decodesEveryField() {
        final SourceRow r = SourceRow.of(quranTranslation());
        assertThat(r.id()).isEqualTo("quran-en-sahih");
        assertThat(r.name()).isEqualTo("Sahih International");
        assertThat(r.abbreviation()).isEqualTo("q-en");
        assertThat(r.direction()).isEqualTo("ltr");
        assertThat(r.license()).isEqualTo("Public Domain");
        assertThat(r.source()).isEqualTo("tanzil.net");
        assertThat(r.type()).isEqualTo("quran");
        assertThat(r.language()).isEqualTo("en");
        assertThat(r.baseText()).isEqualTo("quran-ar");
    }

    @Test
    void ofNullReturnsNull() {
        assertThat(SourceRow.of(null)).isNull();
    }

    @Test
    void rawIsTheBackingArray() {
        final String[] a = bible();
        assertThat(SourceRow.of(a).raw()).isSameAs(a);
    }

    @Test
    void directionDrivesRtl() {
        assertThat(SourceRow.of(quranBase()).isRtl()).isTrue();
        assertThat(SourceRow.of(bible()).isRtl()).isFalse();
    }

    @Test
    void typePredicates() {
        assertThat(SourceRow.of(bible()).isBible()).isTrue();
        assertThat(SourceRow.of(bible()).isQuran()).isFalse();
        assertThat(SourceRow.of(quranBase()).isQuran()).isTrue();
        assertThat(SourceRow.of(quranBase()).hasCompanions()).isTrue();
        assertThat(SourceRow.of(bible()).hasCompanions()).isFalse();
    }

    @Test
    void hadithAlsoHasCompanions() {
        final String[] hadith = {"hadith-bukhari", "Sahih al-Bukhari", "bukhari", "ltr",
                                "PD", "sunnah.com", "hadith", "en", ""};
        assertThat(SourceRow.of(hadith).hasCompanions()).isTrue();
    }

    @Test
    void translationVsPrimary() {
        // A companion translation: quran/hadith type WITH a non-blank baseText.
        assertThat(SourceRow.of(quranTranslation()).isTranslation()).isTrue();
        assertThat(SourceRow.of(quranTranslation()).isPrimary()).isFalse();
        // The Arabic base is a primary despite being a Qur'an (blank baseText).
        assertThat(SourceRow.of(quranBase()).isTranslation()).isFalse();
        assertThat(SourceRow.of(quranBase()).isPrimary()).isTrue();
        // A Bible is always primary.
        assertThat(SourceRow.of(bible()).isPrimary()).isTrue();
    }

    @Test
    void blankBaseTextIsNotATranslation() {
        // baseText present but blank -> still primary (matches the !isBlank guard).
        assertThat(SourceRow.of(quranBase()).isTranslation()).isFalse();
    }

    // ── Short / truncated rows must not throw; missing fields read as the same
    //    defaults the scattered `length > N ? … : …` guards produced. ──

    @Test
    void shortRowFieldsDegradeGracefully() {
        final SourceRow r = SourceRow.of(new String[]{"only-id", "A Name"});   // length 2
        assertThat(r.id()).isEqualTo("only-id");
        assertThat(r.name()).isEqualTo("A Name");
        assertThat(r.abbreviation()).isNull();   // index 2 absent -> null
        assertThat(r.direction()).isNull();
        assertThat(r.license()).isNull();
        assertThat(r.source()).isNull();
        assertThat(r.type()).isEqualTo("");      // type/language default to "" not null
        assertThat(r.language()).isEqualTo("");
        assertThat(r.baseText()).isNull();
        assertThat(r.isRtl()).isFalse();
        assertThat(r.hasCompanions()).isFalse();
        assertThat(r.isPrimary()).isTrue();      // no baseText -> primary
    }

    @Test
    void idOnlyRowIsPrimaryAndTypeless() {
        final SourceRow r = SourceRow.of(new String[]{"x"});
        assertThat(r.type()).isEqualTo("");
        assertThat(r.isBible()).isFalse();
        assertThat(r.isPrimary()).isTrue();
    }
}
