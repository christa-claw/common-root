package org.religioustext.app.ui.views;

import org.religioustext.app.service.CommentQueryService.VerseComment;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a free-text list of scripture references —
 *   "John 1:1, Rom 9:5, Col 1:15-17, 2 Pet 1:1"  (English / USFM)
 *   "Joh. 1:1, Room. 9:5, Kol. 1:15-17"           (Finnish abbreviations)
 *   "Mc 1:1, Hch 2:38"                            (Spanish abbreviations)
 * — into the reader's {@link VerseComment.Ref} shape, so each can be opened in
 * its own column through the existing openRefInNewColumn path. Refs are
 * edition-independent (USFM code + chapter + verse); the column opens in whatever
 * default edition the caller picks.
 *
 * <h2>Locale-first resolution</h2>
 * Resolution is two-tier, which matters because abbreviations collide across
 * languages — "Mc" is Micah in English but Mark (Marco/Marcos) in Italian and
 * Spanish:
 * <ol>
 *   <li><b>The current UI language's table</b> — that language's full names
 *       (inverted from {@code booknames_<lang>.properties}) plus its abbreviations
 *       ({@code bookabbrev_<lang>.properties}). Checked first, so "Mc" means Mark
 *       for a Spanish reader.</li>
 *   <li><b>The global floor</b> — the curated English/USFM table plus every
 *       language's <i>full</i> names. So English abbreviations and any language's
 *       full name always resolve, and a mixed list works.</li>
 * </ol>
 * Localized <i>abbreviations</i> live only in their own language tier (never the
 * global floor), so one language's short forms can never hijack another's. Full
 * names are safe to share globally (they don't collide across languages).
 *
 * Tolerant by design: comma-separated tokens; each is
 * "&lt;book&gt; &lt;chapter&gt;[:&lt;verse&gt;[-&lt;end&gt;]]"; a range opens at its first verse;
 * a chapter-only ref opens at the chapter; unrecognized tokens are skipped.
 *
 * Qur'an references are also recognised via a generic marker word —
 * "Q 19:21", "Surah 19:21", "Sura 2:255" — where the marker means "the
 * following numbers are surah:ayah", not a localized book name. Surah NAMES
 * ("Al-Isra 19:21") are not yet supported; see {@link #QURAN_MARKERS}.
 */
public final class RefListParser {

    private RefListParser() {}

    // book = optional leading numeral + separators ("1." / "1 " / ""), then a
    // Unicode letter and any letters/marks/dots/spaces (so "Song of Songs",
    // "1. Korinttilaiskirje", "Jn.", "Filippiläiskirje"); non-greedy so the
    // trailing "<sp><chapter>" is left for the digits. Then chapter, optional
    // :verse, optional -end (hyphen or en/em dash), captured as group 4.
    private static final Pattern REF = Pattern.compile(
        "^\\s*(\\d?[.\\s]*\\p{L}[\\p{L}\\p{M}.\\s]*?)\\s+(\\d+)"
      + "(?::(\\d+)(?:\\s*[-\\u2013\\u2014]\\s*(\\d+))?)?\\s*$");

    /** Global floor: English/USFM abbreviations + every language's full names.
     *  Normalised keys (Unicode-aware lower-case, non-letter/non-digit stripped). */
    private static final Map<String, String> GLOBAL = new HashMap<>();

    /** Normalised marker words that mean "the following numbers are a Qur'an
     *  surah:ayah, not a Bible book" — checked before any book lookup, in any
     *  language, since these aren't localized book NAMES but a generic "this is
     *  a Qur'an reference" flag. Surah NAMES ("Al-Baqarah 2:255") are not yet
     *  supported — that needs a full 114-entry lexicon, filed as a follow-up. */
    private static final java.util.Set<String> QURAN_MARKERS =
        java.util.Set.of("q", "quran", "koran", "sura", "surah");

    /** Per-language tables: language code → (normalised form → USFM code). Each
     *  holds that language's full names + abbreviations, checked before GLOBAL. */
    private static final Map<String, Map<String, String>> BY_LANG = new HashMap<>();

    /** booknames/bookabbrev bundle suffixes paired with their language code. A
     *  missing file is skipped, so adding bookabbrev_pt.properties (+ "_pt"/"pt"
     *  here) is all it takes to support another language. */
    private static final String[][] BUNDLES = {
        {"",    "en"}, {"_ar", "ar"}, {"_es", "es"}, {"_fi", "fi"}, {"_ru", "ru"},
        {"_sv", "sv"}, {"_zh", "zh"}, {"_de", "de"}, {"_fr", "fr"}, {"_it", "it"},
        {"_hi", "hi"}, {"_he", "he"},
    };

    private static String norm(final String aValue) {
        return aValue == null ? "" : aValue.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static Map<String, String> langMap(final String aLang) {
        return BY_LANG.computeIfAbsent(aLang, k -> new HashMap<>());
    }

    /** Register English/USFM forms into the global floor AND the English tier. */
    private static void put(final String aCode, final String... theNames) {
        final Map<String, String> en = langMap("en");
        for (final String n : theNames) {
            final String k = norm(n);
            GLOBAL.put(k, aCode);
            en.put(k, aCode);
        }
    }

    /** Load a classpath properties file mapping {@code USFM_CODE = form[,form…]}
     *  into the given language tier (and, for full-name bundles, the global floor).
     *  putIfAbsent so the file's first form / an earlier locale wins; the curated
     *  English table (applied first) always wins the global floor. Best-effort: a
     *  missing or unreadable file leaves the tables unchanged. */
    private static void loadForms(final String aResource, final String aLang, final boolean anAlsoGlobal) {
        try (final InputStream in = RefListParser.class.getResourceAsStream(aResource)) {
            if (in == null) return;
            final Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            final Map<String, String> lm = langMap(aLang);
            for (final String code : props.stringPropertyNames()) {
                final String value = props.getProperty(code);
                if (value == null) continue;
                for (final String form : value.split(",")) {
                    final String key = norm(form);
                    if (key.isEmpty()) continue;
                    lm.putIfAbsent(key, code);
                    if (anAlsoGlobal) GLOBAL.putIfAbsent(key, code);
                }
            }
        } catch (final IOException e) {
            // Localized names just won't be available; English/USFM still works.
        }
    }

    static {
        // ── English / USFM (authoritative on the global floor) ────────────
        // Old Testament
        put("GEN", "Genesis", "Gen", "Ge", "Gn");
        put("EXO", "Exodus", "Exod", "Exo", "Ex");
        put("LEV", "Leviticus", "Lev", "Lv");
        put("NUM", "Numbers", "Num", "Nu", "Nm", "Nb");
        put("DEU", "Deuteronomy", "Deut", "Deu", "Dt");
        put("JOS", "Joshua", "Josh", "Jos", "Jsh");
        put("JDG", "Judges", "Judg", "Jdg", "Jdgs");
        put("RUT", "Ruth", "Rut", "Ru");
        put("1SA", "1 Samuel", "1Sam", "1Sa", "1Sm");
        put("2SA", "2 Samuel", "2Sam", "2Sa", "2Sm");
        put("1KI", "1 Kings", "1Kings", "1Kgs", "1Ki", "1Kg");
        put("2KI", "2 Kings", "2Kings", "2Kgs", "2Ki", "2Kg");
        put("1CH", "1 Chronicles", "1Chron", "1Chr", "1Ch");
        put("2CH", "2 Chronicles", "2Chron", "2Chr", "2Ch");
        put("EZR", "Ezra", "Ezra", "Ezr");
        put("NEH", "Nehemiah", "Neh", "Ne");
        put("EST", "Esther", "Esth", "Est", "Es");
        put("JOB", "Job", "Jb");
        put("PSA", "Psalms", "Psalm", "Pslm", "Psa", "Ps", "Pss");
        put("PRO", "Proverbs", "Prov", "Pro", "Prv", "Pr");
        put("ECC", "Ecclesiastes", "Eccles", "Eccl", "Ecc", "Ec", "Qoh");
        put("SNG", "Song of Songs", "Song of Solomon", "Song", "Songs",
                   "SongOfSongs", "SongOfSolomon", "SoS", "Sng", "Canticles", "Cant");
        put("ISA", "Isaiah", "Isa", "Is");
        put("JER", "Jeremiah", "Jer", "Je", "Jr");
        put("LAM", "Lamentations", "Lam", "La");
        put("EZK", "Ezekiel", "Ezekiel", "Ezek", "Eze", "Ezk");
        put("DAN", "Daniel", "Dan", "Da", "Dn");
        put("HOS", "Hosea", "Hos", "Ho");
        put("JOL", "Joel", "Joe", "Jol", "Jl");
        put("AMO", "Amos", "Amo", "Am");
        put("OBA", "Obadiah", "Obad", "Oba", "Ob");
        put("JON", "Jonah", "Jnh", "Jon");
        put("MIC", "Micah", "Mic", "Mi");
        put("NAM", "Nahum", "Nah", "Nam", "Na");
        put("HAB", "Habakkuk", "Hab", "Hb");
        put("ZEP", "Zephaniah", "Zeph", "Zep", "Zp");
        put("HAG", "Haggai", "Hag", "Hg");
        put("ZEC", "Zechariah", "Zech", "Zec", "Zc");
        put("MAL", "Malachi", "Mal", "Ml");
        // New Testament
        put("MAT", "Matthew", "Matt", "Mat", "Mt");
        put("MRK", "Mark", "Mrk", "Mar", "Mk", "Mr");
        put("LUK", "Luke", "Luk", "Lk", "Lu");
        put("JHN", "John", "Jhn", "Jn", "Joh");
        put("ACT", "Acts", "Act", "Ac");
        put("ROM", "Romans", "Rom", "Ro", "Rm");
        put("1CO", "1 Corinthians", "1Cor", "1Co");
        put("2CO", "2 Corinthians", "2Cor", "2Co");
        put("GAL", "Galatians", "Gal", "Ga");
        put("EPH", "Ephesians", "Ephes", "Eph");
        put("PHP", "Philippians", "Phil", "Php", "Pp");
        put("COL", "Colossians", "Col", "Cl");
        put("1TH", "1 Thessalonians", "1Thess", "1Thes", "1Th");
        put("2TH", "2 Thessalonians", "2Thess", "2Thes", "2Th");
        put("1TI", "1 Timothy", "1Tim", "1Ti");
        put("2TI", "2 Timothy", "2Tim", "2Ti");
        put("TIT", "Titus", "Tit", "Ti");
        put("PHM", "Philemon", "Philem", "Phlm", "Phm");
        put("HEB", "Hebrews", "Heb");
        put("JAS", "James", "Jas", "Jam", "Jms", "Jm");
        put("1PE", "1 Peter", "1Pet", "1Pe", "1Pt");
        put("2PE", "2 Peter", "2Pet", "2Pe", "2Pt");
        put("1JN", "1 John", "1Jhn", "1Jn", "1Joh");
        put("2JN", "2 John", "2Jhn", "2Jn", "2Joh");
        put("3JN", "3 John", "3Jhn", "3Jn", "3Joh");
        put("JUD", "Jude", "Jud", "Jd");
        put("REV", "Revelation", "Rev", "Re", "Rv", "Apoc", "Apocalypse");

        // ── Localized tiers ───────────────────────────────────────────────
        // Full names (also seed the global floor) + abbreviations (language tier
        // only, so they never collide across languages on the global floor).
        for (final String[] b : BUNDLES) {
            loadForms("/i18n/booknames"  + b[0] + ".properties", b[1], true);
            loadForms("/i18n/bookabbrev" + b[0] + ".properties", b[1], false);
        }
    }

    /** A parsed token plus its same-chapter end verse ({@code 0} = no range).
     *  The box's grammar only admits an end within the chapter ("2:8-11"), so
     *  one int carries it; cross-chapter spans remain a URL-only feature. */
    public record Span(VerseComment.Ref ref, int endVerse) {}

    /** Span-aware twin of {@link #parse}: same matching, but the range end
     *  survives so the caller can highlight the passage rather than its first
     *  verse. {@link #parse} stays as-is for the comment "add refs" field,
     *  where an anchor is one verse by definition. */
    public static List<Span> parseSpans(final String anInput, final Locale aLocale) {
        final List<Span> out = new ArrayList<>();
        if (anInput == null || anInput.isBlank()) return out;
        final Map<String, String> local = aLocale == null ? null : BY_LANG.get(aLocale.getLanguage());
        for (final String token : anInput.split(",")) {
            final Span sp = parseOneSpan(token, local);
            if (sp != null) out.add(sp);
        }
        return out;
    }

    /** Parse a comma-separated reference list under the given UI locale, skipping
     *  anything unrecognized. Order is preserved; duplicates are kept (the caller
     *  opens one column each). A null locale uses the global floor only. */
    public static List<VerseComment.Ref> parse(final String anInput, final Locale aLocale) {
        final List<VerseComment.Ref> out = new ArrayList<>();
        if (anInput == null || anInput.isBlank()) return out;
        final Map<String, String> local = aLocale == null ? null : BY_LANG.get(aLocale.getLanguage());
        for (final String token : anInput.split(",")) {
            final Span sp = parseOneSpan(token, local);
            if (sp != null) out.add(sp.ref());
        }
        return out;
    }

    /** Locale-independent parse (global floor only). */
    public static List<VerseComment.Ref> parse(final String anInput) {
        return parse(anInput, null);
    }

    /** Parse one token against the given language tier (may be null) then the
     *  global floor, or null when it doesn't match or the book is unknown.
     *  Package-visible for tests. */
    static VerseComment.Ref parseOne(final String aToken, final Map<String, String> aLocal) {
        final Span sp = parseOneSpan(aToken, aLocal);
        return sp == null ? null : sp.ref();
    }

    static Span parseOneSpan(final String aToken, final Map<String, String> aLocal) {
        if (aToken == null || aToken.isBlank()) return null;
        final Matcher m = REF.matcher(aToken);
        if (!m.find()) return null;
        final String key = norm(m.group(1));

        final int chapter = parseInt(m.group(2), -1);
        if (chapter < 1) return null;
        final int verse = parseInt(m.group(3), 0);   // 0 → chapter-level open
        final int endRaw = parseInt(m.group(4), 0);
        final int end = endRaw > verse ? endRaw : 0;   // backwards/equal end = no range

        if (QURAN_MARKERS.contains(key)) {
            // Generic marker ("Q"/"Surah"/...) — the captured "chapter" group IS
            // the surah number; Qur'an addressing has no middle chapter level,
            // so it's carried as bookCode (String) with chapter fixed at 1,
            // matching the existing ?ref=Q.<surah>.<ayah> URL convention.
            return new Span(new VerseComment.Ref(true, String.valueOf(chapter), 1, verse), end);
        }

        String code = aLocal != null ? aLocal.get(key) : null;
        if (code == null) code = GLOBAL.get(key);
        if (code == null) return null;
        return new Span(new VerseComment.Ref(false, code, chapter, verse), end);
    }

    static VerseComment.Ref parseOne(final String aToken) {
        return parseOne(aToken, null);
    }

    private static int parseInt(final String aString, final int aFallback) {
        if (aString == null) return aFallback;
        try { return Integer.parseInt(aString.trim()); }
        catch (final NumberFormatException e) { return aFallback; }
    }
}
