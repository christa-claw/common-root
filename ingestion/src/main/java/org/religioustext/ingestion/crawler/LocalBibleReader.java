package org.religioustext.ingestion.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Bible content from a local clone of wldeh/bible-api.
 * Expected path: $BIBLE_API_SOURCE/bibles/{translation}/ (default ~/bible-api-source)
 *
 * Structure:
 *   books/{bookId}/chapters/{N}/verses/{N}.json
 *   books/{bookId}/chapters/{N}.json  (ignored — we use verse files)
 *
 * Books, chapters and verses are discovered dynamically from
 * the folder structure — no hardcoded book lists needed.
 */
@Component
public class LocalBibleReader {

    private static final Logger log = LoggerFactory.getLogger(LocalBibleReader.class);

    /** Root of the local bible-api clone; override with {@code BIBLE_API_SOURCE}. */
    private static final String LOCAL_BASE = System.getenv().getOrDefault("BIBLE_API_SOURCE",
        System.getProperty("user.home") + "/bible-api-source") + "/bibles";
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Returns true if the local clone has data for the given translation.
     */
    public boolean isAvailable(final String aTranslationCode) {
        return new File(LOCAL_BASE + "/" + aTranslationCode + "/books").isDirectory();
    }

    /**
     * Returns all book folder names in canonical order (alphabetical by folder name —
     * actual canonical ordering is applied by the book definitions in BibleApiCrawler).
     */
    public List<String> listBooks(final String aTranslationCode) {
        final File booksDir = new File(LOCAL_BASE + "/" + aTranslationCode + "/books");
        if (!booksDir.exists()) return List.of();

        final String[] books = booksDir.list(
            (dir, name) -> new File(dir, name).isDirectory());

        if (books == null) return List.of();
        Arrays.sort(books);
        return Arrays.asList(books);
    }

    /**
     * Fetches all chapters for a single book.
     * Returns list of chapter maps: {chapterNumber: N, verses: [{number: N, text: "..."}]}
     */
    public List<Map<String, Object>> fetchChapters(
             final String aTranslationCode
            , final String aBookId) {

        final List<Map<String, Object>> chapters = new ArrayList<>();
        final File chaptersDir = new File(
            LOCAL_BASE + "/" + aTranslationCode + "/books/" + aBookId + "/chapters");

        if (!chaptersDir.exists()) return chapters;

        // List only numbered directories (not .json files)
        final File[] chapterDirs = chaptersDir.listFiles(
            f -> f.isDirectory() && f.getName().matches("\\d+"));

        if (chapterDirs == null) return chapters;

        Arrays.sort(chapterDirs
            , Comparator.comparingInt(f -> Integer.parseInt(f.getName())));

        for (final File chapterDir : chapterDirs) {
            final int chapterNumber = Integer.parseInt(chapterDir.getName());
            final List<Map<String, Object>> verses = fetchVerses(chapterDir);
            if (!verses.isEmpty()) {
                chapters.add(Map.of(
                     "chapterNumber", chapterNumber
                    , "verses",        verses));
            }
        }

        log.debug("Read {} chapters for {}/{}", chapters.size(), aTranslationCode, aBookId);
        return chapters;
    }

    /**
     * Fetches all verses from a chapter directory.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchVerses(final File aChapterDir) {
        final List<Map<String, Object>> verses = new ArrayList<>();

        final File versesDir = new File(aChapterDir, "verses");
        if (!versesDir.exists()) return verses;

        final File[] verseFiles = versesDir.listFiles(
            f -> f.isFile() && f.getName().matches("\\d+\\.json"));

        if (verseFiles == null) return verses;

        Arrays.sort(verseFiles
            , Comparator.comparingInt(f ->
                Integer.parseInt(f.getName().replace(".json", ""))));

        for (final File verseFile : verseFiles) {
            try {
                final Map<String, Object> verseData =
                    mapper.readValue(verseFile, Map.class);
                final int    verseNumber = Integer.parseInt(
                    verseFile.getName().replace(".json", ""));
                final String text        = (String) verseData.get("text");
                if (text != null && !text.isBlank()) {
                    verses.add(Map.of("number", verseNumber, "text", text));
                }
            } catch (final Exception e) {
                log.error("Error reading verse file {}: {}"
                    , verseFile.getAbsolutePath(), e.getMessage());
            }
        }

        return verses;
    }
}
