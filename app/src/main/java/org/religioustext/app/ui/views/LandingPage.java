// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data behind the {@code /read/:code} per-source landing pages
 * (see {@link LandingPageView}, {@link org.religioustext.app.config.LandingSeoListener}).
 *
 * <p>Deliberately a plain data map, not one Java class per language — adding a
 * new landing page is adding an entry here (and to {@code sitemap.xml}), not
 * writing new code. Same philosophy as {@code patch_lineage.py} and
 * {@code channels.properties} elsewhere in this project.
 *
 * <p>Content is written directly here rather than through the i18n bundles:
 * this is page-specific prose (not reused UI chrome), one page = one language
 * = one purpose-written text, so there is nothing to translate INTO — the
 * Hindi Bible's landing page is written for an English-speaking searcher
 * asking "is there a Hindi Bible online", not localised UI copy.
 *
 * <p>SEO note: {@code title}/{@code description} are what search engines and
 * link-preview scrapers see, so they are keyword-honest and distinct per
 * page — never templated boilerplate with only the language name swapped
 * (that pattern reads as a "doorway page" to search engines and can hurt
 * rather than help).
 */
public final class LandingPage {

    /** URL slug after {@code /read/} — also the route parameter value. */
    public final String slug;
    public final String title;              // <title> and og:title
    public final String description;        // <meta name=description> and og:description
    /** Deep link into the reader — a source token from docs/link-format.md,
     *  matching the tokens AboutView's text cards already use. */
    public final String readerHref;
    public final String heroHeading;
    public final String heroBody;           // may contain \n\n paragraph breaks
    /** A short passage in the source's own script, for visual anchoring —
     *  purely decorative, not a substitute for reading the text. */
    public final String scriptSample;
    public final String scriptSampleGloss;  // what the sample says, in English

    LandingPage(final String aSlug, final String aTitle, final String aDescription,
                final String aReaderHref, final String aHeroHeading, final String aHeroBody,
                final String aScriptSample, final String aScriptSampleGloss) {
        slug = aSlug;
        title = aTitle;
        description = aDescription;
        readerHref = aReaderHref;
        heroHeading = aHeroHeading;
        heroBody = aHeroBody;
        scriptSample = aScriptSample;
        scriptSampleGloss = aScriptSampleGloss;
    }

    // ── The pages ─────────────────────────────────────────────────────
    // First batch: three pages proving the pattern. Extend by adding entries
    // — remember to also add the URL to sitemap.xml.

    public static final Map<String, LandingPage> PAGES = new LinkedHashMap<>();
    static {
        PAGES.put("hi", new LandingPage(
            "hi",
            "Hindi Bible Online — हिन्दी बाइबिल | Common Root",
            "Read the Indian Revised Version (IRV) Hindi Bible online, free — "
                + "verse by verse, in Devanagari script, no account needed. "
                + "Compare side by side with English, Hebrew, and other translations.",
            "/reader?c1.src=irvhin",
            "हिन्दी बाइबिल — The Hindi Bible, Free and Open",
            "The Indian Revised Version (IRV) is a complete, modern Hindi "
                + "translation of the Bible, licensed CC BY-SA 4.0 and free to "
                + "read here — no account, no ads, no tracking.\n\n"
                + "Common Root lets you open the Hindi text beside the King "
                + "James Version, the Hebrew Masoretic text, or any of two "
                + "dozen other editions, scrolling in sync so you can compare "
                + "translations verse by verse.",
            "आदि में परमेश्वर ने आकाश और पृथ्वी की सृष्टि की।",
            "\"In the beginning God created the heaven and the earth.\" — Genesis 1:1"
        ));
        PAGES.put("he", new LandingPage(
            "he",
            "Hebrew Bible Online — עברית | Masoretic Text + Delitzsch NT | Common Root",
            "Read the Hebrew Bible online free: the Masoretic Old Testament "
                + "and Franz Delitzsch's Hebrew New Testament, verse by verse. "
                + "Also available: the Westminster Leningrad Codex with full "
                + "cantillation marks.",
            "/reader?c1.src=hebm",
            "עברית — The Hebrew Bible, Old and New Testament",
            "This edition pairs the traditional Masoretic Hebrew Old Testament "
                + "with Franz Delitzsch's classic 19th-century Hebrew "
                + "translation of the New Testament — so the whole Bible can "
                + "be read in Hebrew, cover to cover.\n\n"
                + "For the Old Testament alone in its full scholarly form, "
                + "Common Root also carries the Westminster Leningrad Codex, "
                + "complete with vowel points and cantillation marks, and — "
                + "uniquely — readable in the Hebrew Bible's own traditional "
                + "order: Torah, Prophets, Writings, ending at Chronicles.",
            "בְּרֵאשִׁית בָּרָא אֱלֹהִים אֵת הַשָּׁמַיִם וְאֵת הָאָרֶץ",
            "\"In the beginning God created the heaven and the earth.\" — Genesis 1:1"
        ));
        PAGES.put("quran", new LandingPage(
            "quran",
            "Quran Online — Arabic with English Translation | Common Root",
            "Read the Quran online free: the Uthmani Arabic text with the "
                + "Pickthall English translation shown beneath each ayah, or "
                + "the Sablukov Russian translation. Switch between the "
                + "traditional reading order and the order of revelation.",
            "/reader?c1.src=q-ar&c1.companion=1",
            "القرآن الكريم — The Quran, Arabic and Translation Together",
            "The Uthmani Arabic text is the base of every column, with an "
                + "English or Russian translation displayed beneath each ayah "
                + "— never replacing the Arabic, always alongside it, the way "
                + "Islamic tradition treats translation as commentary rather "
                + "than scripture itself.\n\n"
                + "Common Root is one of the few readers that lets you switch "
                + "between the Quran's traditional (mus'haf) order and the "
                + "order scholars reconstruct for its revelation, Meccan and "
                + "Medinan surahs marked so you can read either way.",
            "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ",
            "\"In the name of Allah, the Beneficent, the Merciful\" — the opening of every surah but one"
        ));

        PAGES.put("zh", new LandingPage(
            "zh",
            "Chinese Bible Online — \u548c\u5408\u672c\u5723\u7ecf | Common Root",
            "\u514d\u8d39\u5728\u7ebf\u9605\u8bfb\u548c\u5408\u672c\u5723\u7ecf\uff08\u7b80\u4f53\uff09\uff0c\u9010\u8282\u5bf9\u7167\u82f1\u6587\u94a6\u5b9a\u672c\u3001"
                + "\u5e0c\u4f2f\u6765\u6587\u4e0e\u5e0c\u814a\u6587\u539f\u6587\u3002\u65e0\u9700\u6ce8\u518c\uff0c\u6ca1\u6709\u5e7f\u544a\uff0c\u4e0d\u505a\u8ffd\u8e2a\u3002 "
                + "Read the Chinese Union Version online, free, beside the original languages.",
            "/reader?c1.src=cuv&lang=zh",
            "\u548c\u5408\u672c\u5723\u7ecf \u2014 \u514d\u8d39\u5728\u7ebf\u9605\u8bfb\uff0c\u4e0e\u539f\u6587\u9010\u8282\u5bf9\u7167",
            "\u300a\u5b98\u8bdd\u548c\u5408\u672c\u300b\u7684\u7ffb\u8bd1\u5de5\u4f5c\u59cb\u4e8e\u4e00\u516b\u4e5d\u4e00\u5e74\uff0c\u5386\u65f6\u4e8c\u5341\u4f59\u5e74\uff0c"
                + "\u4e00\u4e5d\u4e00\u4e5d\u5e74\u51fa\u7248\u3002\u4e00\u767e\u591a\u5e74\u540e\uff0c\u5b83\u4ecd\u7136\u662f\u534e\u8bed\u6559\u4f1a\u6700\u901a\u884c\u7684"
                + "\u5723\u7ecf\u8bd1\u672c\uff0c\u4e5f\u5df2\u8fdb\u5165\u516c\u6709\u9886\u57df\uff0c\u53ef\u4ee5\u81ea\u7531\u9605\u8bfb\u4e0e\u8f6c\u8f7d\u3002\n\n"
                + "\u5728 Common Root\uff0c\u4f60\u53ef\u4ee5\u628a\u548c\u5408\u672c\u4e0e\u82f1\u6587\u94a6\u5b9a\u672c\u3001\u5e0c\u4f2f\u6765\u6587\u9a6c\u6240\u62c9\u6587\u672c"
                + "\u6216\u5e0c\u814a\u6587\u516c\u8ba4\u6587\u672c\u5e76\u6392\u6253\u5f00\uff0c\u9010\u8282\u540c\u6b65\u6eda\u52a8\uff0c\u9010\u53e5\u5bf9\u7167\u3002"
                + "\u9605\u8bfb\u987a\u5e8f\u53ef\u4ee5\u5728\u6b63\u5178\u6b21\u5e8f\u3001\u6210\u4e66\u5e74\u4ee3\u4e0e\u5e0c\u4f2f\u6765\u5723\u7ecf\u7684\u4f20\u7edf"
                + "\u6b21\u5e8f\u4e4b\u95f4\u5207\u6362\u3002\u65e0\u9700\u6ce8\u518c\uff0c\u6ca1\u6709\u5e7f\u544a\uff0c\u4e0d\u505a\u8ffd\u8e2a\u3002",
            "\u8d77\u521d\uff0c\u795e\u521b\u9020\u5929\u5730\u3002",
            "\"In the beginning God created the heaven and the earth.\" \u2014 Genesis 1:1"
        ));
    }

    // ── Path resolution ───────────────────────────────────────────────

    /** URL prefix these pages live under. */
    public static final String PREFIX = "/read/";

    /**
     * Resolve a raw request path info to the landing page it addresses, or
     * {@code null} if it addresses none.
     *
     * <p>BOTH SEO listeners route on this one method — {@code LandingSeoListener}
     * to decide what to write, {@code SocialPreviewInitListener.isLandingPage} to
     * decide what to leave alone. Their path domains must be exact complements: a
     * path that neither claims gets no canonical and no preview tags at all. Two
     * private copies of "strip the prefix and look it up" drifted apart on exactly
     * that edge (a trailing slash), so there is deliberately only one copy now.
     *
     * <p>Tolerates a query string and trailing slashes, both of which a crawler,
     * a shared link or a hand-typed URL adds without thinking.
     *
     * @param aPathInfo the request path info, may be {@code null}
     * @return the addressed page, or {@code null}
     */
    public static LandingPage forPathInfo(final String aPathInfo) {
        if (aPathInfo == null || aPathInfo.isBlank()) return null;
        String p = aPathInfo.trim();
        final int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (!p.startsWith("/")) p = "/" + p;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.startsWith(PREFIX)) return null;
        return PAGES.get(p.substring(PREFIX.length()));
    }
}
