package org.religioustext.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Which chapters have generated audio, read from the manifest the TTS job
 * writes at the root of the audio tree.
 *
 * <p>The manifest is produced by scanning the audio directory rather than by
 * trusting the generator's ledger, so a chapter appears here only when its mp3
 * genuinely exists. That property is the whole point: the reader uses this to
 * decide whether to offer audio, and offering a file that 404s is worse than
 * offering nothing.
 *
 * <p>Everything here fails soft. A missing, empty, unreadable or malformed
 * manifest means "no audio anywhere" — never an exception reaching a view.
 * Audio is an enhancement; the reader must work identically without it.
 *
 * <p>Reloading is deliberately dumb: the file's last-modified time is checked
 * at most once a minute and the whole thing is re-parsed when it moves. Audio
 * lands once a night in a batch, so a watch service would be machinery for a
 * problem nobody has.
 */
@Service
public class AudioIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(AudioIndexService.class);

    /** How long a parse is trusted before the file's mtime is consulted again. */
    private static final long STALE_AFTER_MS = 60_000L;

    private final Path manifest;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Text id to voice, for building URLs. Empty when there is no manifest. */
    private volatile Map<String, String> voices = Map.of();
    /**
     * Text id to book code to chapter number to <em>file name</em>.
     *
     * <p>The file name comes from the manifest rather than being rebuilt here.
     * The generator owns the naming convention (zero-padded chapter plus book
     * code, so a listing sorts 1, 2, 10 and a lone downloaded file still says
     * what it is); if that changes again, this class does not.
     */
    private volatile Map<String, Map<String, NavigableMap<Integer, String>>> chapters = Map.of();
    /**
     * Text id to book code to the <em>directory</em> that book's files sit in.
     *
     * <p>Read from the manifest for the same reason the file name is: the
     * generator owns the layout. Book folders are numbered by canonical
     * position ("01_GEN") so a listing sorts in reading order, and an older
     * manifest that predates that has no "dir" at all — such a book falls
     * back to its bare code, which is exactly what the folder was called.
     */
    private volatile Map<String, Map<String, String>> bookDirs = Map.of();

    private volatile long loadedFromMtime = -1L;
    private volatile long lastCheckedAt;

    public AudioIndexService(
            @Value("${commonroot.audio.index:/srv/audio/index.json}") final String aManifestPath) {
        this.manifest = Paths.get(aManifestPath);
        LOG.info("audio manifest: {}", this.manifest);
    }

    /** True when this edition has audio for at least one chapter of any book. */
    public boolean hasAudio(final String aTextId) {
        if (aTextId == null) return false;
        refreshIfStale();
        final Map<String, NavigableMap<Integer, String>> byBook = chapters.get(aTextId);
        return byBook != null && !byBook.isEmpty();
    }

    /** True when this book has audio for at least one chapter. */
    public boolean hasAudio(final String aTextId, final String aBookCode) {
        return !chaptersWithAudio(aTextId, aBookCode).isEmpty();
    }

    /**
     * Chapter numbers of this book that have audio, ascending; empty when none.
     * Never null, so callers can size a "12 of 50" label without a null check.
     */
    public NavigableSet<Integer> chaptersWithAudio(final String aTextId, final String aBookCode) {
        if (aTextId == null || aBookCode == null) return Collections.emptyNavigableSet();
        refreshIfStale();
        final Map<String, NavigableMap<Integer, String>> byBook = chapters.get(aTextId);
        if (byBook == null) return Collections.emptyNavigableSet();
        final NavigableMap<Integer, String> found = byBook.get(aBookCode);
        return found == null ? Collections.emptyNavigableSet() : found.navigableKeySet();
    }

    /**
     * Public URL of one chapter's mp3, or empty when that chapter has no audio.
     *
     * <p>The path carries the voice because the same edition could be re-voiced
     * later without invalidating what is already published.
     */
    public Optional<String> mp3Url(final String aTextId, final String aBookCode, final int aChapter) {
        refreshIfStale();
        final Map<String, NavigableMap<Integer, String>> byBook = chapters.get(aTextId);
        if (byBook == null) return Optional.empty();
        final NavigableMap<Integer, String> files = byBook.get(aBookCode);
        final String file = files == null ? null : files.get(aChapter);
        final String voice = voices.get(aTextId);
        if (file == null || voice == null) return Optional.empty();
        final Map<String, String> dirs = bookDirs.get(aTextId);
        final String dir = dirs == null ? null : dirs.get(aBookCode);
        return Optional.of("/audio/" + aTextId + "/" + voice + "/"
                + (dir == null ? aBookCode : dir) + "/" + file);
    }

    /** Companion JSON of per-verse millisecond offsets, for the player. */
    public Optional<String> offsetsUrl(final String aTextId, final String aBookCode, final int aChapter) {
        return mp3Url(aTextId, aBookCode, aChapter)
                .map(url -> url.substring(0, url.length() - 4) + ".json");
    }

    /** Editions with any audio at all, for diagnostics and the About page. */
    public List<String> textsWithAudio() {
        refreshIfStale();
        return List.copyOf(chapters.keySet());
    }

    // ---------------------------------------------------------------- loading

    private void refreshIfStale() {
        final long now = System.currentTimeMillis();
        if (now - lastCheckedAt < STALE_AFTER_MS) return;
        lastCheckedAt = now;
        try {
            if (!Files.isReadable(manifest)) {
                if (loadedFromMtime != -1L) {
                    LOG.warn("audio manifest disappeared at {} — reporting no audio", manifest);
                    clear();
                }
                return;
            }
            final long mtime = Files.getLastModifiedTime(manifest).toMillis();
            if (mtime == loadedFromMtime) return;
            reload(mtime);
        } catch (final IOException | RuntimeException e) {
            // Never let a bad manifest break the reader, and keep whatever was
            // last parsed rather than dropping to empty on a transient read
            // error while rsync is mid-flight.
            LOG.warn("audio manifest could not be refreshed ({}): {}", manifest, e.toString());
        }
    }

    private void reload(final long aMtime) throws IOException {
        final JsonNode root = mapper.readTree(manifest.toFile());
        final JsonNode texts = root == null ? null : root.get("texts");
        if (texts == null || !texts.isObject()) {
            LOG.warn("audio manifest has no texts object — reporting no audio");
            clear();
            loadedFromMtime = aMtime;
            return;
        }
        final Map<String, String> newVoices = new TreeMap<>();
        final Map<String, Map<String, NavigableMap<Integer, String>>> newChapters = new TreeMap<>();
        final Map<String, Map<String, String>> newDirs = new TreeMap<>();
        texts.fields().forEachRemaining(textEntry -> {
            final String textId = textEntry.getKey();
            final JsonNode text = textEntry.getValue();
            final JsonNode voice = text.get("voice");
            final JsonNode books = text.get("books");
            if (voice == null || books == null || !books.isObject()) return;
            final Map<String, NavigableMap<Integer, String>> byBook = new TreeMap<>();
            final Map<String, String> dirsForText = new TreeMap<>();
            books.fields().forEachRemaining(bookEntry -> {
                final JsonNode chs = bookEntry.getValue().get("chapters");
                if (chs == null || !chs.isObject()) return;
                final NavigableMap<Integer, String> files = new TreeMap<>();
                chs.fields().forEachRemaining(chEntry -> {
                    final JsonNode file = chEntry.getValue().get("file");
                    if (file == null) return;   // no file name, no offer of audio
                    try { files.put(Integer.valueOf(chEntry.getKey()), file.asText()); }
                    catch (final NumberFormatException ignored) { /* not a chapter */ }
                });
                if (!files.isEmpty()) {
                    byBook.put(bookEntry.getKey(), Collections.unmodifiableNavigableMap(files));
                    final JsonNode dir = bookEntry.getValue().get("dir");
                    if (dir != null && !dir.asText().isEmpty()) {
                        dirsForText.put(bookEntry.getKey(), dir.asText());
                    }
                }
            });
            if (!byBook.isEmpty()) {
                newVoices.put(textId, voice.asText());
                newChapters.put(textId, Collections.unmodifiableMap(byBook));
                newDirs.put(textId, Collections.unmodifiableMap(dirsForText));
            }
        });
        voices = Collections.unmodifiableMap(newVoices);
        chapters = Collections.unmodifiableMap(newChapters);
        bookDirs = Collections.unmodifiableMap(newDirs);
        loadedFromMtime = aMtime;
        LOG.info("audio manifest loaded: {} edition(s), {} chapter(s)",
                newChapters.size(),
                newChapters.values().stream()
                        .flatMap(b -> b.values().stream()).mapToInt(java.util.Map::size).sum());
    }

    private void clear() {
        voices = Map.of();
        chapters = Map.of();
        bookDirs = Map.of();
        loadedFromMtime = -1L;
    }
}
