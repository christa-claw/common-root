// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.ingestion;

import org.religioustext.ingestion.crawler.ApiBibleCrawler;
import org.religioustext.ingestion.crawler.LocalBibleReader;
import org.religioustext.ingestion.model.IngestionRequest;
import org.religioustext.ingestion.parser.BibleApiParser;
import org.religioustext.ingestion.parser.BibleApiParser.ParsedChapter;
import org.religioustext.ingestion.parser.BibleApiParser.ParsedVerse;
import org.religioustext.ingestion.store.xmldb.BaseXStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ingests Bible translations from the local wldeh/bible-api clone.
 * Uses API.Bible for chapter titles if available, leaves empty if not.
 *
 * Book discovery is dynamic — reads whatever books exist in the local clone.
 * This naturally includes DC/Apocryphal books where present.
 */
@Service
public class LocalIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LocalIngestionService.class);
    private static final int    MAX_THREADS  = 5;
    // Maps local folder names to canonical book names and metadata
    private static final Map<String, BookMeta> BOOK_META = buildBookMeta();

    private static final CrawledBook POISON_PILL = new CrawledBook(null, 0, null, null, null);

    private final LocalBibleReader localReader;
    private final ApiBibleCrawler  apiBibleCrawler;
    private final BibleApiParser   parser;
    private final BaseXStore       store;

    public LocalIngestionService(
             final LocalBibleReader localReader
            , final ApiBibleCrawler  apiBibleCrawler
            , final BibleApiParser   parser
            , final BaseXStore       store) {
        this.localReader     = localReader;
        this.apiBibleCrawler = apiBibleCrawler;
        this.parser          = parser;
        this.store           = store;
    }

    public String ingest(final IngestionRequest aRequest) throws Exception {
        // sourceUrl format: "local:{localCode}:{apiBibleId}"
        final String[] urlParts      = aRequest.getSourceUrl().split(":");
        final String translationCode = urlParts.length > 1 ? urlParts[1] : aRequest.getSourceUrl();
        final String abbreviation    = aRequest.getAbbreviation();
        final String documentId      = "bible-" + abbreviation.toLowerCase()
            + (aRequest.getYear() != null ? "-" + aRequest.getYear() : "");

        log.info("Starting local ingestion: {} from {}", abbreviation, translationCode);

        if (!localReader.isAvailable(translationCode)) {
            throw new IllegalStateException(
                "Local clone not found for: " + translationCode
                + " under $BIBLE_API_SOURCE/bibles/");
        }

        // Fetch API.Bible titles if apiBibleId is configured
        final Map<String, String> titleMap = fetchTitles(aRequest);
        log.info("Fetched {} chapter titles from API.Bible", titleMap.size());

        // Discover books from local clone
        final List<String> bookFolders = localReader.listBooks(translationCode);
        log.info("Found {} books in local clone", bookFolders.size());

        // Create root document
        if (store.exists(documentId)) {
            store.delete(documentId);
        }
        store.createRootDocument(
             documentId
            , aRequest.getSourceType().name().toLowerCase()
            , aRequest.getTranslation()
            , aRequest.getAbbreviation()
            , aRequest.getBcp47Language()
            , aRequest.getIso639_3()
            , aRequest.getDirection()
            , aRequest.getLicense()
            , aRequest.getYear()
            , aRequest.getRegion()
            , aRequest.getAttributionUrl());

        // Producer/consumer pipeline
        final BlockingQueue<CrawledBook> queue =
            new LinkedBlockingQueue<>(MAX_THREADS * 2);
        final AtomicInteger totalVerses = new AtomicInteger(0);
        final AtomicInteger errors      = new AtomicInteger(0);

        // Insert thread
        final Thread insertThread = new Thread(() -> {
            MDC.put("translation", abbreviation);
            MDC.put("book", "INSERT");
            int inserted = 0;
            while (true) {
                try {
                    final CrawledBook crawled = queue.take();
                    if (crawled == POISON_PILL) break;

                    final String bookXml = parser.serialiseBook(
                         null
                        , crawled.bookName()
                        , null
                        , crawled.canonicalOrder()
                        , crawled.testament()
                        , crawled.chapters()
                        , crawled.bookCode());

                    store.insertBook(documentId, bookXml);
                    final int verses = crawled.chapters().stream()
                        .mapToInt(c -> c.verses().size()).sum();
                    totalVerses.addAndGet(verses);
                    inserted++;
                    log.info("Inserted [{}/{}] {} ({} verses)"
                        , inserted, bookFolders.size(), crawled.bookName(), verses);

                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (final Exception e) {
                    errors.incrementAndGet();
                    log.error("Insert error: {}", e.getMessage(), e);
                }
            }
        }, "local-insert");
        insertThread.start();

        // Crawler pool
        final ExecutorService pool = Executors.newFixedThreadPool(MAX_THREADS);

        bookFolders.forEach(bookFolder -> pool.submit(() -> {
            final BookMeta meta     = BOOK_META.getOrDefault(bookFolder
                , new BookMeta(toDisplayName(bookFolder), "DC"));
            final String bookName   = meta.name();
            final String testament  = meta.testament();

            try {
                MDC.put("translation", abbreviation);
                MDC.put("book", bookName);

                final List<Map<String, Object>> rawChapters =
                    localReader.fetchChapters(translationCode, bookFolder);

                // Apply titles from API.Bible title map
                final List<ParsedChapter> chapters = rawChapters.stream()
                    .map(raw -> {
                        final int chapterNum = (Integer) raw.get("chapterNumber");
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> rawVerses =
                            (List<Map<String, Object>>) raw.get("verses");

                        // Build title key e.g. "GEN.1"
                        final String bookCode  = toApiCode(bookFolder);
                        final String titleKey  = bookCode + "." + chapterNum;
                        final String title     = titleMap.get(titleKey); // null if not available

                        final List<ParsedVerse> verses = rawVerses.stream()
                            .map(v -> new ParsedVerse(
                                 (Integer) v.get("number")
                                , bookCode + "." + chapterNum + "." + v.get("number")
                                , (String) v.get("text")))
                            .toList();

                        return new ParsedChapter(
                             bookCode + "." + chapterNum
                            , bookName + " " + chapterNum
                            , title
                            , verses);
                    })
                    .toList();

                queue.put(new CrawledBook(bookName, meta.canonicalOrder(), testament, toApiCode(bookFolder), chapters));

            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                errors.incrementAndGet();
                log.error("Error processing {}: {}", bookFolder, e.getMessage(), e);
            } finally {
                MDC.clear();
            }
        }));

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.HOURS);
        // Signal insert thread to stop cleanly via poison pill
        queue.put(POISON_PILL);
        insertThread.join();

        store.setTotalVerses(documentId, totalVerses.get());
        log.info("Local ingestion complete: {} — {} verses, {} errors"
            , documentId, totalVerses.get(), errors.get());

        return documentId;
    }

    /**
     * Fetches chapter titles from API.Bible for this translation.
     * Returns map of "GEN.1" -> "The Creation" etc.
     * Returns empty map if no apiBibleId configured or API call fails.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> fetchTitles(final IngestionRequest aRequest) {
        final Map<String, String> titles = new LinkedHashMap<>();
        final String apiBibleId = extractApiBibleId(aRequest);
        if (apiBibleId == null) return titles;

        try {
            final List<Map<String, Object>> books = apiBibleCrawler.fetchBooks(apiBibleId);
            for (final Map<String, Object> book : books) {
                final String bookId = (String) book.get("id");
                final List<Map<String, Object>> chapters =
                    (List<Map<String, Object>>) book.get("chapters");
                if (chapters == null) continue;

                for (final Map<String, Object> chapterMeta : chapters) {
                    final String chapterId = (String) chapterMeta.get("id");
                    if (chapterId == null || chapterId.contains("intro")) continue;
                    try {
                        final Map<String, Object> chapterData =
                            apiBibleCrawler.fetchChapter(apiBibleId, chapterId);
                        final String title = extractFirstTitle(chapterData);
                        if (title != null) {
                            titles.put(chapterId, title);
                        }
                        Thread.sleep(250); // rate limit
                    } catch (final Exception e) {
                        log.debug("No title for {}: {}", chapterId, e.getMessage());
                    }
                }
            }
        } catch (final Exception e) {
            log.warn("Could not fetch titles from API.Bible: {}", e.getMessage());
        }

        return titles;
    }

    @SuppressWarnings("unchecked")
    private String extractFirstTitle(final Map<String, Object> aChapterData) {
        final List<Object> content = (List<Object>) aChapterData.get("content");
        if (content == null) return null;
        for (final Object item : content) {
            if (!(item instanceof Map)) continue;
            final Map<String, Object> node = (Map<String, Object>) item;
            final Map<String, Object> attrs = (Map<String, Object>) node.get("attrs");
            final String style = attrs != null ? (String) attrs.get("style") : null;
            if (style != null && style.startsWith("s") && !"sp".equals(style)) {
                final List<Object> items = (List<Object>) node.get("items");
                if (items == null) continue;
                final StringBuilder sb = new StringBuilder();
                for (final Object child : items) {
                    if (child instanceof Map) {
                        final Map<String, Object> childMap = (Map<String, Object>) child;
                        if ("text".equals(childMap.get("type"))) {
                            sb.append(childMap.get("text"));
                        }
                    }
                }
                final String title = sb.toString().trim();
                if (!title.isEmpty()) return title;
            }
        }
        return null;
    }

    private String extractApiBibleId(final IngestionRequest aRequest) {
        // apiBibleId stored in sourceUrl for local ingestion requests
        final String url = aRequest.getSourceUrl();
        if (url != null && url.startsWith("apibible:")) {
            return url.substring("apibible:".length());
        }
        return null;
    }

    private String toDisplayName(final String aFolder) {
        // Convert folder name to display name e.g. "songofsolomon" -> "Song of Solomon"
        return aFolder.substring(0, 1).toUpperCase() + aFolder.substring(1);
    }

    private String toApiCode(final String aFolder) {
        // Convert local folder name to API.Bible book code
        final BookMeta meta = BOOK_META.get(aFolder);
        return meta != null ? meta.apiCode() : aFolder.toUpperCase().substring(0, 3);
    }

    private record CrawledBook(
         String            bookName
        , int               canonicalOrder
        , String            testament
        , String            bookCode
        , List<ParsedChapter> chapters) {}

    private record BookMeta(String name, String testament, String apiCode, int canonicalOrder) {
        BookMeta(final String name, final String testament) {
            this(name, testament, name.substring(0, Math.min(3, name.length())).toUpperCase(), 0);
        }
    }

    private static Map<String, BookMeta> buildBookMeta() {
        final Map<String, BookMeta> m = new LinkedHashMap<>();
        // OT
        m.put("genesis",         new BookMeta("Genesis",          "OT", "GEN",  1));
        m.put("exodus",          new BookMeta("Exodus",           "OT", "EXO",  2));
        m.put("leviticus",       new BookMeta("Leviticus",        "OT", "LEV",  3));
        m.put("numbers",         new BookMeta("Numbers",          "OT", "NUM",  4));
        m.put("deuteronomy",     new BookMeta("Deuteronomy",      "OT", "DEU",  5));
        m.put("joshua",          new BookMeta("Joshua",           "OT", "JOS",  6));
        m.put("judges",          new BookMeta("Judges",           "OT", "JDG",  7));
        m.put("ruth",            new BookMeta("Ruth",             "OT", "RUT",  8));
        m.put("1samuel",         new BookMeta("1 Samuel",         "OT", "1SA",  9));
        m.put("2samuel",         new BookMeta("2 Samuel",         "OT", "2SA", 10));
        m.put("1kings",          new BookMeta("1 Kings",          "OT", "1KI", 11));
        m.put("2kings",          new BookMeta("2 Kings",          "OT", "2KI", 12));
        m.put("1chronicles",     new BookMeta("1 Chronicles",     "OT", "1CH", 13));
        m.put("2chronicles",     new BookMeta("2 Chronicles",     "OT", "2CH", 14));
        m.put("ezra",            new BookMeta("Ezra",             "OT", "EZR", 15));
        m.put("nehemiah",        new BookMeta("Nehemiah",         "OT", "NEH", 16));
        m.put("esther",          new BookMeta("Esther",           "OT", "EST", 17));
        m.put("job",             new BookMeta("Job",              "OT", "JOB", 18));
        m.put("psalms",          new BookMeta("Psalms",           "OT", "PSA", 19));
        m.put("proverbs",        new BookMeta("Proverbs",         "OT", "PRO", 20));
        m.put("ecclesiastes",    new BookMeta("Ecclesiastes",     "OT", "ECC", 21));
        m.put("songofsolomon",   new BookMeta("Song of Solomon",  "OT", "SNG", 22));
        m.put("isaiah",          new BookMeta("Isaiah",           "OT", "ISA", 23));
        m.put("jeremiah",        new BookMeta("Jeremiah",         "OT", "JER", 24));
        m.put("lamentations",    new BookMeta("Lamentations",     "OT", "LAM", 25));
        m.put("ezekiel",         new BookMeta("Ezekiel",          "OT", "EZK", 26));
        m.put("daniel",          new BookMeta("Daniel",           "OT", "DAN", 27));
        m.put("hosea",           new BookMeta("Hosea",            "OT", "HOS", 28));
        m.put("joel",            new BookMeta("Joel",             "OT", "JOL", 29));
        m.put("amos",            new BookMeta("Amos",             "OT", "AMO", 30));
        m.put("obadiah",         new BookMeta("Obadiah",          "OT", "OBA", 31));
        m.put("jonah",           new BookMeta("Jonah",            "OT", "JON", 32));
        m.put("micah",           new BookMeta("Micah",            "OT", "MIC", 33));
        m.put("nahum",           new BookMeta("Nahum",            "OT", "NAM", 34));
        m.put("habakkuk",        new BookMeta("Habakkuk",         "OT", "HAB", 35));
        m.put("zephaniah",       new BookMeta("Zephaniah",        "OT", "ZEP", 36));
        m.put("haggai",          new BookMeta("Haggai",           "OT", "HAG", 37));
        m.put("zechariah",       new BookMeta("Zechariah",        "OT", "ZEC", 38));
        m.put("malachi",         new BookMeta("Malachi",          "OT", "MAL", 39));
        // NT
        m.put("matthew",         new BookMeta("Matthew",          "NT", "MAT", 40));
        m.put("mark",            new BookMeta("Mark",             "NT", "MRK", 41));
        m.put("luke",            new BookMeta("Luke",             "NT", "LUK", 42));
        m.put("john",            new BookMeta("John",             "NT", "JHN", 43));
        m.put("acts",            new BookMeta("Acts",             "NT", "ACT", 44));
        m.put("romans",          new BookMeta("Romans",           "NT", "ROM", 45));
        m.put("1corinthians",    new BookMeta("1 Corinthians",    "NT", "1CO", 46));
        m.put("2corinthians",    new BookMeta("2 Corinthians",    "NT", "2CO", 47));
        m.put("galatians",       new BookMeta("Galatians",        "NT", "GAL", 48));
        m.put("ephesians",       new BookMeta("Ephesians",        "NT", "EPH", 49));
        m.put("philippians",     new BookMeta("Philippians",      "NT", "PHP", 50));
        m.put("colossians",      new BookMeta("Colossians",       "NT", "COL", 51));
        m.put("1thessalonians",  new BookMeta("1 Thessalonians",  "NT", "1TH", 52));
        m.put("2thessalonians",  new BookMeta("2 Thessalonians",  "NT", "2TH", 53));
        m.put("1timothy",        new BookMeta("1 Timothy",        "NT", "1TI", 54));
        m.put("2timothy",        new BookMeta("2 Timothy",        "NT", "2TI", 55));
        m.put("titus",           new BookMeta("Titus",            "NT", "TIT", 56));
        m.put("philemon",        new BookMeta("Philemon",         "NT", "PHM", 57));
        m.put("hebrews",         new BookMeta("Hebrews",          "NT", "HEB", 58));
        m.put("james",           new BookMeta("James",            "NT", "JAS", 59));
        m.put("1peter",          new BookMeta("1 Peter",          "NT", "1PE", 60));
        m.put("2peter",          new BookMeta("2 Peter",          "NT", "2PE", 61));
        m.put("1john",           new BookMeta("1 John",           "NT", "1JN", 62));
        m.put("2john",           new BookMeta("2 John",           "NT", "2JN", 63));
        m.put("3john",           new BookMeta("3 John",           "NT", "3JN", 64));
        m.put("jude",            new BookMeta("Jude",             "NT", "JUD", 65));
        m.put("revelation",      new BookMeta("Revelation",       "NT", "REV", 66));
        // DC / Apocrypha
        m.put("tobit",           new BookMeta("Tobit",            "DC", "TOB", 67));
        m.put("judith",          new BookMeta("Judith",           "DC", "JDT", 68));
        m.put("1maccabees",      new BookMeta("1 Maccabees",      "DC", "1MA", 69));
        m.put("2maccabees",      new BookMeta("2 Maccabees",      "DC", "2MA", 70));
        m.put("wisdom",          new BookMeta("Wisdom",           "DC", "WIS", 71));
        m.put("ecclesiasticus",  new BookMeta("Sirach",            "DC", "SIR", 72));
        m.put("baruch",          new BookMeta("Baruch",           "DC", "BAR", 73));
        m.put("1esdras",         new BookMeta("1 Esdras",         "DC", "1ES", 74));
        m.put("2esdras",         new BookMeta("2 Esdras",         "DC", "2ES", 75));
        m.put("3maccabees",      new BookMeta("3 Maccabees",      "DC", "3MA", 76));
        m.put("4maccabees",      new BookMeta("4 Maccabees",      "DC", "4MA", 77));
        m.put("manasseh",        new BookMeta("Prayer of Manasseh","DC", "MAN", 78));
        m.put("susanna",         new BookMeta("Susanna",          "DC", "SUS", 79));
        m.put("songofthethree",  new BookMeta("Song of the Three","DC", "S3Y", 80));
        m.put("belandthedragon", new BookMeta("Bel and the Dragon","DC", "BEL", 81));
        m.put("esther(greek)",   new BookMeta("Esther (Greek)",    "DC", "ESG", 82));
        // WEB/DRA alternate folder names
        m.put("wisdomofsolomon", new BookMeta("Wisdom",            "DC", "WIS", 71));
        m.put("prayerofmanasses",new BookMeta("Prayer of Manasseh","DC", "MAN", 78));
        m.put("daniel(greek)",   new BookMeta("Daniel (Greek)",    "DC", "DAG", 83));
        m.put("psalm151",        new BookMeta("Psalm 151",         "DC", "PS2", 84));
        // RVR09 Spanish folder names
        m.put("génesis",         new BookMeta("Génesis",           "OT", "GEN",  1));
        m.put("éxodo",           new BookMeta("Éxodo",             "OT", "EXO",  2));
        m.put("levítico",        new BookMeta("Levítico",          "OT", "LEV",  3));
        m.put("números",         new BookMeta("Números",           "OT", "NUM",  4));
        m.put("deuteronomio",    new BookMeta("Deuteronomio",      "OT", "DEU",  5));
        m.put("josué",           new BookMeta("Josué",             "OT", "JOS",  6));
        m.put("jueces",          new BookMeta("Jueces",            "OT", "JDG",  7));
        m.put("rut",             new BookMeta("Rut",               "OT", "RUT",  8));
        m.put("1samuel",         new BookMeta("1 Samuel",          "OT", "1SA",  9));
        m.put("2samuel",         new BookMeta("2 Samuel",          "OT", "2SA", 10));
        m.put("1reyes",          new BookMeta("1 Reyes",           "OT", "1KI", 11));
        m.put("2reyes",          new BookMeta("2 Reyes",           "OT", "2KI", 12));
        m.put("1crónicas",       new BookMeta("1 Crónicas",        "OT", "1CH", 13));
        m.put("2crónicas",       new BookMeta("2 Crónicas",        "OT", "2CH", 14));
        m.put("esdras",          new BookMeta("Esdras",            "OT", "EZR", 15));
        m.put("nehemías",        new BookMeta("Nehemías",          "OT", "NEH", 16));
        m.put("ester",           new BookMeta("Ester",             "OT", "EST", 17));
        m.put("salmos",          new BookMeta("Salmos",            "OT", "PSA", 19));
        m.put("proverbios",      new BookMeta("Proverbios",        "OT", "PRO", 20));
        m.put("eclesiastés",     new BookMeta("Eclesiastés",       "OT", "ECC", 21));
        m.put("cantares",        new BookMeta("Cantares",          "OT", "SNG", 22));
        m.put("isaías",          new BookMeta("Isaías",            "OT", "ISA", 23));
        m.put("jeremías",        new BookMeta("Jeremías",          "OT", "JER", 24));
        m.put("lamentaciones",   new BookMeta("Lamentaciones",     "OT", "LAM", 25));
        m.put("ezequiel",        new BookMeta("Ezequiel",          "OT", "EZK", 26));
        m.put("oseas",           new BookMeta("Oseas",             "OT", "HOS", 28));
        m.put("amós",            new BookMeta("Amós",              "OT", "AMO", 30));
        m.put("abdías",          new BookMeta("Abdías",            "OT", "OBA", 31));
        m.put("jonás",           new BookMeta("Jonás",             "OT", "JON", 32));
        m.put("miqueas",         new BookMeta("Miqueas",           "OT", "MIC", 33));
        m.put("nahúm",           new BookMeta("Nahúm",             "OT", "NAM", 34));
        m.put("habacuc",         new BookMeta("Habacuc",           "OT", "HAB", 35));
        m.put("sofonías",        new BookMeta("Sofonías",          "OT", "ZEP", 36));
        m.put("hageo",           new BookMeta("Hageo",             "OT", "HAG", 37));
        m.put("zacarías",        new BookMeta("Zacarías",          "OT", "ZEC", 38));
        m.put("malaquías",       new BookMeta("Malaquías",         "OT", "MAL", 39));
        m.put("Sanmateo",        new BookMeta("San Mateo",         "NT", "MAT", 40));
        m.put("Sanmarcos",       new BookMeta("San Marcos",        "NT", "MRK", 41));
        m.put("Sanlucas",        new BookMeta("San Lucas",         "NT", "LUK", 42));
        m.put("Sanjuan",         new BookMeta("San Juan",          "NT", "JHN", 43));
        m.put("hechos",          new BookMeta("Hechos",            "NT", "ACT", 44));
        m.put("romanos",         new BookMeta("Romanos",           "NT", "ROM", 45));
        m.put("1corintios",      new BookMeta("1 Corintios",       "NT", "1CO", 46));
        m.put("2corintios",      new BookMeta("2 Corintios",       "NT", "2CO", 47));
        m.put("gálatas",         new BookMeta("Gálatas",           "NT", "GAL", 48));
        m.put("efesios",         new BookMeta("Efesios",           "NT", "EPH", 49));
        m.put("filipenses",      new BookMeta("Filipenses",        "NT", "PHP", 50));
        m.put("colosenses",      new BookMeta("Colosenses",        "NT", "COL", 51));
        m.put("1tesalonicenses", new BookMeta("1 Tesalonicenses",  "NT", "1TH", 52));
        m.put("2tesalonicenses", new BookMeta("2 Tesalonicenses",  "NT", "2TH", 53));
        m.put("1timoteo",        new BookMeta("1 Timoteo",         "NT", "1TI", 54));
        m.put("2timoteo",        new BookMeta("2 Timoteo",         "NT", "2TI", 55));
        m.put("tito",            new BookMeta("Tito",              "NT", "TIT", 56));
        m.put("filemón",         new BookMeta("Filemón",           "NT", "PHM", 57));
        m.put("hebreos",         new BookMeta("Hebreos",           "NT", "HEB", 58));
        m.put("santiago",        new BookMeta("Santiago",          "NT", "JAS", 59));
        m.put("1pedro",          new BookMeta("1 Pedro",           "NT", "1PE", 60));
        m.put("2pedro",          new BookMeta("2 Pedro",           "NT", "2PE", 61));
        m.put("1juan",           new BookMeta("1 Juan",            "NT", "1JN", 62));
        m.put("2juan",           new BookMeta("2 Juan",            "NT", "2JN", 63));
        m.put("3juan",           new BookMeta("3 Juan",            "NT", "3JN", 64));
        m.put("judas",           new BookMeta("Judas",             "NT", "JUD", 65));
        m.put("apocalipsis",     new BookMeta("Apocalipsis",       "NT", "REV", 66));
        // ── Additional folder-name variants across other local-clone editions ──
        // English Song of Songs spellings (BSB uses "song", others "songofsongs")
        m.put("song",            new BookMeta("Song of Solomon",   "OT", "SNG", 22));
        m.put("songofsongs",     new BookMeta("Song of Solomon",   "OT", "SNG", 22));
        // DC spelling variants (Revised Version 1885)
        m.put("sirach",          new BookMeta("Sirach",            "DC", "SIR", 72));
        m.put("songofthe3holychildren", new BookMeta("Song of the Three", "DC", "S3Y", 80));
        // Spanish gospel folders without "San" (BES / PDDPT / VBL) + spelling variants
        m.put("mateo",           new BookMeta("Mateo",             "NT", "MAT", 40));
        m.put("marcos",          new BookMeta("Marcos",            "NT", "MRK", 41));
        m.put("lucas",           new BookMeta("Lucas",             "NT", "LUK", 42));
        m.put("juan",            new BookMeta("Juan",              "NT", "JHN", 43));
        m.put("ageo",            new BookMeta("Hageo",             "OT", "HAG", 37));
        m.put("cantardeloscantares", new BookMeta("Cantar de los Cantares", "OT", "SNG", 22));
        return m;
    }
}
