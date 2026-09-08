package org.religioustext.ingestion;

import org.religioustext.ingestion.crawler.ApiBibleCrawler;
import org.religioustext.ingestion.model.IngestionRequest;
import org.religioustext.ingestion.parser.BibleApiParser;
import org.religioustext.ingestion.parser.BibleApiParser.ParsedChapter;
import org.religioustext.ingestion.store.xmldb.BaseXStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orchestrates the API.Bible ingestion pipeline.
 *
 * Producer/consumer threading:
 *   5 crawler threads fetch book chapters in parallel (network bound)
 *   1 insert thread serialises writes to BaseX (no write conflicts)
 *
 *   CrawlerThread-1 ─┐
 *   CrawlerThread-2 ─┤
 *   CrawlerThread-3 ─┼──► BlockingQueue<CrawledBook> ──► InsertThread ──► BaseX
 *   CrawlerThread-4 ─┤
 *   CrawlerThread-5 ─┘
 *
 * Rate limit: API.Bible allows 5K requests/day.
 * At ~50 chapters/book x 66 books = ~3300 requests per translation.
 * One translation fits comfortably in one day.
 */
@Service
public class IngestionService {

    private static final Logger log             = LoggerFactory.getLogger(IngestionService.class);
    private static final int    MAX_CRAWL_THREADS = 5;
    private static final CrawledBook POISON_PILL = new CrawledBook(null, null, 0, null, null, 0, null);

    private final ApiBibleCrawler crawler;
    private final BibleApiParser  parser;
    private final BaseXStore      store;

    public IngestionService(
             final ApiBibleCrawler crawler
            , final BibleApiParser  parser
            , final BaseXStore      store) {
        this.crawler = crawler;
        this.parser  = parser;
        this.store   = store;
    }

    // Maps API.Bible book IDs to canonical full names
    private static final Map<String, String> BOOK_NAMES = Map.ofEntries(
        Map.entry("GEN", "Genesis"),        Map.entry("EXO", "Exodus"),
        Map.entry("LEV", "Leviticus"),       Map.entry("NUM", "Numbers"),
        Map.entry("DEU", "Deuteronomy"),     Map.entry("JOS", "Joshua"),
        Map.entry("JDG", "Judges"),          Map.entry("RUT", "Ruth"),
        Map.entry("1SA", "1 Samuel"),        Map.entry("2SA", "2 Samuel"),
        Map.entry("1KI", "1 Kings"),         Map.entry("2KI", "2 Kings"),
        Map.entry("1CH", "1 Chronicles"),    Map.entry("2CH", "2 Chronicles"),
        Map.entry("EZR", "Ezra"),            Map.entry("NEH", "Nehemiah"),
        Map.entry("EST", "Esther"),          Map.entry("JOB", "Job"),
        Map.entry("PSA", "Psalms"),          Map.entry("PRO", "Proverbs"),
        Map.entry("ECC", "Ecclesiastes"),    Map.entry("SNG", "Song of Solomon"),
        Map.entry("ISA", "Isaiah"),          Map.entry("JER", "Jeremiah"),
        Map.entry("LAM", "Lamentations"),    Map.entry("EZK", "Ezekiel"),
        Map.entry("DAN", "Daniel"),          Map.entry("HOS", "Hosea"),
        Map.entry("JOL", "Joel"),            Map.entry("AMO", "Amos"),
        Map.entry("OBA", "Obadiah"),         Map.entry("JON", "Jonah"),
        Map.entry("MIC", "Micah"),           Map.entry("NAM", "Nahum"),
        Map.entry("HAB", "Habakkuk"),        Map.entry("ZEP", "Zephaniah"),
        Map.entry("HAG", "Haggai"),          Map.entry("ZEC", "Zechariah"),
        Map.entry("MAL", "Malachi"),         Map.entry("MAT", "Matthew"),
        Map.entry("MRK", "Mark"),            Map.entry("LUK", "Luke"),
        Map.entry("JHN", "John"),            Map.entry("ACT", "Acts"),
        Map.entry("ROM", "Romans"),          Map.entry("1CO", "1 Corinthians"),
        Map.entry("2CO", "2 Corinthians"),   Map.entry("GAL", "Galatians"),
        Map.entry("EPH", "Ephesians"),       Map.entry("PHP", "Philippians"),
        Map.entry("COL", "Colossians"),      Map.entry("1TH", "1 Thessalonians"),
        Map.entry("2TH", "2 Thessalonians"), Map.entry("1TI", "1 Timothy"),
        Map.entry("2TI", "2 Timothy"),       Map.entry("TIT", "Titus"),
        Map.entry("PHM", "Philemon"),        Map.entry("HEB", "Hebrews"),
        Map.entry("JAS", "James"),           Map.entry("1PE", "1 Peter"),
        Map.entry("2PE", "2 Peter"),         Map.entry("1JN", "1 John"),
        Map.entry("2JN", "2 John"),          Map.entry("3JN", "3 John"),
        Map.entry("JUD", "Jude"),            Map.entry("REV", "Revelation")
    );

    private String resolveBookName(final String aBookId, final String anApiFallback) {
        return BOOK_NAMES.getOrDefault(aBookId, anApiFallback);
    }

    @SuppressWarnings("unchecked")
    public String ingest(final IngestionRequest aRequest) throws Exception {
        final String translationId = aRequest.getAbbreviation();
        final String documentId    = buildDocumentId(aRequest);
        final String bibleId       = extractBibleId(aRequest);

        log.info("Starting ingestion: {} -> {} (API.Bible: {})"
            , translationId, documentId, bibleId);
        final long startMs = System.currentTimeMillis();

        // Step 1: Fetch book list
        final List<Map<String, Object>> books = crawler.fetchBooks(bibleId);
        log.info("Found {} books", books.size());

        // Step 2: Create empty root document in BaseX
        if (store.exists(documentId)) {
            log.info("Document exists — deleting for fresh ingestion");
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

        // Step 3: Producer/consumer pipeline
        final BlockingQueue<CrawledBook> queue =
            new LinkedBlockingQueue<>(MAX_CRAWL_THREADS * 2);

        final AtomicInteger totalVerses  = new AtomicInteger(0);
        final AtomicInteger crawlErrors  = new AtomicInteger(0);
        final AtomicInteger insertErrors = new AtomicInteger(0);

        // ── Insert thread ─────────────────────────────────────────────
        final Thread insertThread = new Thread(() -> {
            MDC.put("translation", translationId);
            MDC.put("book", "INSERT");
            int inserted = 0;
            while (true) {
                try {
                    final CrawledBook crawled = queue.take();
                    if (crawled == POISON_PILL) break;

                    final String bookXml = parser.serialiseBook(
                         null
                        , crawled.bookName()
                        , crawled.bookAltName()
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
                        , inserted, books.size(), crawled.bookName(), verses);

                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (final Exception e) {
                    insertErrors.incrementAndGet();
                    log.error("Insert error: {}", e.getMessage(), e);
                }
            }
        }, "basex-insert");
        insertThread.start();

        // ── Crawler thread pool ───────────────────────────────────────
        final ExecutorService crawlPool =
            Executors.newFixedThreadPool(MAX_CRAWL_THREADS);

        // Determine canonical order and testament from book position
        final int[] orderCounter = {0};

        books.forEach(book -> crawlPool.submit(() -> {
            final String bookId   = (String) book.get("id");
            final String bookName = resolveBookName(bookId, (String) book.get("name"));
            final int    order;
            synchronized (orderCounter) { order = ++orderCounter[0]; }
            final String testament = order <= 39 ? "OT" : "NT";

            try {
                MDC.put("translation", translationId);
                MDC.put("book", bookName);
                log.info("Crawling book {}: {}", order, bookName);

                final List<Map<String, Object>> rawChapters =
                    crawler.fetchChaptersForBook(bibleId, book);
                final List<ParsedChapter> chapters = rawChapters.stream()
                    .map(parser::parseChapter)
                    .toList();

                queue.put(new CrawledBook(
                     bookName, null, order, testament, chapters, 0, bookId));

            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                crawlErrors.incrementAndGet();
                log.error("Crawl error for {}: {}", bookName, e.getMessage());
            } finally {
                MDC.clear();
            }
        }));

        crawlPool.shutdown();
        crawlPool.awaitTermination(2, TimeUnit.HOURS);
        queue.put(POISON_PILL);
        insertThread.join();

        // Step 4: Update total verse count
        store.setTotalVerses(documentId, totalVerses.get());

        final long elapsed = System.currentTimeMillis() - startMs;
        log.info("Ingestion complete: {} — {} verses, {} crawl errors, {} insert errors, {}ms"
            , documentId, totalVerses.get(), crawlErrors.get(), insertErrors.get(), elapsed);

        return documentId;
    }

    private String buildDocumentId(final IngestionRequest aRequest) {
        return aRequest.getSourceType().name().toLowerCase()
            + "-" + aRequest.getAbbreviation().toLowerCase()
            + (aRequest.getYear() != null ? "-" + aRequest.getYear() : "");
    }

    private String extractBibleId(final IngestionRequest aRequest) {
        // sourceUrl is set to https://rest.api.bible/v1/bibles/{bibleId}
        final String url = aRequest.getSourceUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private record CrawledBook(
         String            bookName
        , String            bookAltName
        , int               canonicalOrder
        , String            testament
        , List<ParsedChapter> chapters
        , int               unused
        , String            bookCode) {}
}
