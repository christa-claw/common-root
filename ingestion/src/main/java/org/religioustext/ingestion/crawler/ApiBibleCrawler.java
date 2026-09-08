package org.religioustext.ingestion.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Crawls Bible content from API.Bible (scripture.api.bible).
 *
 * API.Bible provides clean formatted text with:
 *   - Section headings / chapter titles
 *   - Footnotes as separate fields (not appended to verse text)
 *   - Proper Unicode, no pilcrow markers
 *   - 5K requests/day on free tier
 *
 * Endpoints used:
 *   GET /v1/bibles/{bibleId}/books
 *   GET /v1/bibles/{bibleId}/chapters/{chapterId}
 *       ?content-type=json&include-notes=false&include-titles=true
 *       &include-chapter-numbers=false&include-verse-numbers=true
 */
@Component
public class ApiBibleCrawler {

    private static final Logger log = LoggerFactory.getLogger(ApiBibleCrawler.class);

    private static final String BASE_URL    = "https://rest.api.bible/v1";
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${apibible.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public ApiBibleCrawler(final RestTemplate aRestTemplate) {
        this.restTemplate = aRestTemplate;
    }

    /**
     * Fetches all books for a given Bible ID.
     * Returns list of maps with: id, bookId, number, name, nameLong
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchBooks(final String aBibleId) {
        log.info("Fetching books for Bible: {}", aBibleId);
        final String url = BASE_URL + "/bibles/" + aBibleId + "/books"
            + "?include-chapters=true";
        final Map<String, Object> response =
            restTemplate.exchange(url, HttpMethod.GET
                , new HttpEntity<>(buildHeaders()), Map.class).getBody();

        return (List<Map<String, Object>>) response.get("data");
    }

    /**
     * Fetches a single chapter with full verse content.
     * Returns structured chapter data including verses and section titles.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchChapter(
             final String aBibleId
            , final String aChapterId) {

        log.debug("Fetching chapter: {}/{}", aBibleId, aChapterId);

        final String url = BASE_URL + "/bibles/" + aBibleId
            + "/chapters/" + aChapterId
            + "?content-type=json"
            + "&include-notes=false"
            + "&include-titles=true"
            + "&include-chapter-numbers=false"
            + "&include-verse-numbers=true"
            + "&include-verse-spans=false";

        final Map<String, Object> response =
            restTemplate.exchange(url, HttpMethod.GET
                , new HttpEntity<>(buildHeaders()), Map.class).getBody();

        return (Map<String, Object>) response.get("data");
    }

    /**
     * Fetches all chapters for a single book.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> fetchChaptersForBook(
             final String              aBibleId
            , final Map<String, Object> aBookData) {

        final List<Map<String, Object>> chapters = new ArrayList<>();
        final List<Map<String, Object>> chapterList =
            (List<Map<String, Object>>) aBookData.get("chapters");

        if (chapterList == null) return chapters;

        for (final Map<String, Object> chapterMeta : chapterList) {
            final String chapterId = (String) chapterMeta.get("id");
            try {
                final Map<String, Object> chapter = fetchChapter(aBibleId, chapterId);
                chapters.add(chapter);
                // Respect rate limit — 5K/day = ~300ms between requests to be safe
                Thread.sleep(300);
            } catch (final Exception e) {
                log.error("Failed to fetch chapter {}: {}", chapterId, e.getMessage());
            }
        }

        return chapters;
    }

    private HttpHeaders buildHeaders() {
        final HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", apiKey);
        headers.set("accept", "application/json");
        return headers;
    }
}
