// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SocialPreviewInitListenerLanguageTest {

    @ParameterizedTest(name = "Path: {0}, Query: {1} => Language: {2}")
    @CsvSource({
            "/hi, , hi",
            "/hi/, , hi",
            "/ar, , ar",
            "/he/, , he",
            "/es, null, es",
            "/reader/ar, , ar",
            "/reader/he/, , he",
            "/, lang=hi, hi",
            "/reader, lang=ar, ar",
            "/reader/, lang=he, he",
            "/hi, lang=ar, hi",
            "/ar/, lang=es, ar",
            "/reader/he, lang=hi, he",
            "/reader, , en",
            "/, , en",
            "/unknown, , en",
            "/reader/unknown/, , en",
            "/, lang=invalid, en",
    })
    void testExtractLanguage(final String aPath, final String aQuery, final String aExpected) {
        final String actualQuery = "(empty)".equals(aQuery) ? "" : aQuery;
        final String result = SocialPreviewInitListener.extractLanguage(aPath, actualQuery);
        assertEquals(aExpected, result, "Failed for path=" + aPath + ", query=" + actualQuery);
    }

    @Test
    void testLanguageMetaArabic() {
        final String lang = SocialPreviewInitListener.extractLanguage("/ar", null);
        assertEquals("ar", lang);
    }

    @Test
    void testLanguageMetaHindi() {
        final String lang = SocialPreviewInitListener.extractLanguage("/hi", null);
        assertEquals("hi", lang);
    }

    @Test
    void testLanguageMetaHebrew() {
        final String lang = SocialPreviewInitListener.extractLanguage("/reader/he", null);
        assertEquals("he", lang);
    }

    @Test
    void testLanguageQueryParamTakesPrecedenceWhenNoPath() {
        final String lang = SocialPreviewInitListener.extractLanguage("/reader", "lang=es");
        assertEquals("es", lang);
    }

    @Test
    void testPathPrecedenceOverQuery() {
        final String lang = SocialPreviewInitListener.extractLanguage("/hi", "lang=ar");
        assertEquals("hi", lang, "Path should take precedence over query param");
    }

    @Test
    void testTrailingSlashHandledCorrectly() {
        assertEquals(
                SocialPreviewInitListener.extractLanguage("/hi", null),
                SocialPreviewInitListener.extractLanguage("/hi/", null),
                "Trailing slash should not affect language extraction"
        );
    }

    @Test
    void testRegionalVariantHandling() {
        final String lang = SocialPreviewInitListener.extractLanguage("/", "lang=ar-EG");
        assertEquals("ar", lang, "Regional variants should extract primary language code");
    }

    @Test
    void testReaderPathLanguageExtraction() {
        assertEquals("ar", SocialPreviewInitListener.extractLanguage("/reader/ar", null));
        assertEquals("he", SocialPreviewInitListener.extractLanguage("/reader/he/", null));
        assertEquals("hi", SocialPreviewInitListener.extractLanguage("/reader/hi", null));
    }

    @Test
    void testKnownPrefixesNotTreatedAsLanguage() {
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/reader", null));
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/read", null));
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/edition", null));
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/admin", null));
    }

    @Test
    void testRootPathDefaultsToEnglish() {
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/", null));
        assertEquals("en", SocialPreviewInitListener.extractLanguage(null, null));
        assertEquals("en", SocialPreviewInitListener.extractLanguage("", null));
    }

    @Test
    void testQueryParamWithMissingLanguageDefaultsToEnglish() {
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/", "other=value"));
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/", "lang="));
        assertEquals("en", SocialPreviewInitListener.extractLanguage("/", "lang=invalid!"));
    }

    @Test
    void testMultipleQueryParams() {
        assertEquals("hi", SocialPreviewInitListener.extractLanguage("/", "other=value&lang=hi&more=data"));
        assertEquals("ar", SocialPreviewInitListener.extractLanguage("/", "c1.src=hi&lang=ar"));
    }

    @Test
    void testCanonicalUrlDropsLanguagePath() {
        final String canonRoot = SocialPreviewInitListener.canonicalUrlFor("/");
        assertEquals("https://common-root.org/", canonRoot);
    }
}
