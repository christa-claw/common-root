// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.i18n;

import java.util.Locale;
import java.util.Map;

/**
 * Locale-aware rendering of a plain integer (a verse number, a chapter
 * number, a UI step counter) as a numeral string. The underlying int is
 * never touched by this class — it is a display-time formatter only, so
 * URLs, element ids, map keys, search and lineage linking all keep using
 * the raw number exactly as before.
 *
 * <p>Registered by language code in {@link #BY_LANGUAGE} — same philosophy
 * as {@link EditionInfo#PAGES} and {@code channels.properties} elsewhere in
 * this project: a language that should get its own numeral convention is an
 * entry in this map plus a small {@link NumeralFormatter}, not a change to
 * any call site. A language with no entry here falls back to {@link
 * #WESTERN}, the plain {@code String.valueOf} behaviour every call site had
 * before this class existed.
 *
 * <p>Only Arabic and Hebrew are registered today. Several other locales in
 * {@link LocaleUtil#LOCALES} have their own historical numeral systems —
 * Devanagari for Hindi, Chinese numerals, Church Slavonic for Russian — but
 * none of them is the live convention for how this project's texts are
 * actually printed (checked against how each language's Bible societies
 * typeset chapter/verse numbers today, not assumed from the existence of
 * the numeral system). Dropping one in later is additive: write a {@link
 * NumeralFormatter} the shape of {@link EasternArabicIndicNumerals} or
 * {@link HebrewLetterNumerals} and add one line to {@link #BY_LANGUAGE}.
 */
public final class Numerals {

    private Numerals() { }

    /** Plain Western digits — identical to the {@code String.valueOf} every
     *  call site used before this class existed, and the fallback for any
     *  language with no entry in {@link #BY_LANGUAGE}. */
    public static final NumeralFormatter WESTERN = String::valueOf;

    private static final Map<String, NumeralFormatter> BY_LANGUAGE = Map.of(
        "ar", new EasternArabicIndicNumerals()
        , "he", new HebrewLetterNumerals()
    );

    /** Render {@code aNumber} the way {@code aLocale} would print it. */
    public static String format(final int aNumber, final Locale aLocale) {
        final NumeralFormatter formatter = (aLocale == null)
            ? null
            : BY_LANGUAGE.get(aLocale.getLanguage());
        return (formatter != null) ? formatter.format(aNumber) : WESTERN.format(aNumber);
    }

    /** Whether a language code (e.g. {@code "ar"}, {@code "he"}) has its own
     *  registered numeral convention — used to decide whether a "native
     *  numerals" toggle is worth showing for a given source at all. Null,
     *  blank and unregistered languages all answer false. */
    public static boolean supports(final String aLanguage) {
        return aLanguage != null && BY_LANGUAGE.containsKey(aLanguage.toLowerCase(Locale.ROOT));
    }
}

/** A language's way of rendering a non-negative integer as a numeral string. */
@FunctionalInterface
interface NumeralFormatter {
    String format(int aNumber);
}

/** Eastern Arabic-Indic digits (٠-٩) — the convention this project's Arabic
 *  texts, every Qurʾān edition in the corpus included, are printed in. A
 *  straight digit substitution; any non-digit character passes through
 *  unchanged. */
final class EasternArabicIndicNumerals implements NumeralFormatter {

    private static final char[] DIGITS =
        {'٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩'};

    @Override
    public String format(final int aNumber) {
        final String western = String.valueOf(aNumber);
        final StringBuilder result = new StringBuilder(western.length());
        for (final char digit : western.toCharArray()) {
            result.append(Character.isDigit(digit) ? DIGITS[digit - '0'] : digit);
        }
        return result.toString();
    }
}

/** Traditional Hebrew letter-numerals (ones/tens/hundreds from the Hebrew
 *  alphabet), with the standard geresh/gershayim punctuation and the
 *  customary 15/16 exceptions (ט״ו, ט״ז) that avoid spelling letter
 *  combinations read as the divine name. Falls back to plain digits outside
 *  the 1–999 range this project's chapter and verse numbers live in (the
 *  longest chapter in the corpus, Psalm 119, has 176 verses). */
final class HebrewLetterNumerals implements NumeralFormatter {

    private static final String[] ONES =
        {"", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט"};
    private static final String[] TENS =
        {"", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ"};
    private static final String[] HUNDREDS =
        {"", "ק", "ר", "ש", "ת", "תק", "תר", "תש", "תת", "תתק"};

    @Override
    public String format(final int aNumber) {
        if (aNumber < 1 || aNumber > 999) {
            return String.valueOf(aNumber);
        }
        final int hundreds  = aNumber / 100;
        final int remainder = aNumber % 100;
        return punctuate(HUNDREDS[hundreds] + tensAndOnes(remainder));
    }

    private static String tensAndOnes(final int aRemainder) {
        if (aRemainder == 15) {
            return "טו";
        }
        if (aRemainder == 16) {
            return "טז";
        }
        return TENS[aRemainder / 10] + ONES[aRemainder % 10];
    }

    private static String punctuate(final String aLetters) {
        if (aLetters.length() < 2) {
            return aLetters.isEmpty() ? aLetters : aLetters + "׳";
        }
        return aLetters.substring(0, aLetters.length() - 1)
            + "״"
            + aLetters.substring(aLetters.length() - 1);
    }
}
