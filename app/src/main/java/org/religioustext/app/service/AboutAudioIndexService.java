// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Which About-page sections have generated narration audio, read from the
 * manifest {@code scripts/audio/tts_about.py} writes next to the chapter
 * manifest {@link AudioIndexService} already reads.
 *
 * <p>Deliberately a separate, small service rather than folded into
 * {@link AudioIndexService}: the shape is flatter (locale + section, no
 * book/chapter nesting, no per-verse offsets), the manifest is a different
 * file, and the two features should never be able to break each other.
 *
 * <p>Same discipline as {@link AudioIndexService}: the manifest is produced
 * by scanning disk, not trusted from the generator's ledger, so a section
 * appears here only when its mp3 genuinely exists; everything fails soft (a
 * missing, empty, unreadable or malformed manifest means "no audio
 * anywhere", never an exception reaching a view); reload is checked at most
 * once a minute, since this fills in over hours/days, not live.
 */
@Service
public class AboutAudioIndexService {

    private static final Logger LOG = LoggerFactory.getLogger(AboutAudioIndexService.class);

    private static final long STALE_AFTER_MS = 60_000L;

    private final Path manifest;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Locale to section key to mp3 file name. */
    private volatile Map<String, Map<String, String>> sections = Map.of();

    private volatile long loadedFromMtime = -1L;
    private volatile long lastCheckedAt;

    public AboutAudioIndexService(
            @Value("${commonroot.audio.aboutIndex:/srv/audio/about-index.json}") final String aManifestPath) {
        this.manifest = Paths.get(aManifestPath);
        LOG.info("about-audio manifest: {}", this.manifest);
    }

    /** True when this locale/section pair has a generated narration. */
    public boolean hasAudio(final String aLocale, final String aSection) {
        if (aLocale == null || aSection == null) return false;
        refreshIfStale();
        final Map<String, String> bySection = sections.get(aLocale);
        return bySection != null && bySection.containsKey(aSection);
    }

    /** Public URL of one section's mp3, or empty when not generated (yet). */
    public Optional<String> mp3Url(final String aLocale, final String aSection) {
        refreshIfStale();
        final Map<String, String> bySection = sections.get(aLocale);
        final String file = bySection == null ? null : bySection.get(aSection);
        if (file == null) return Optional.empty();
        return Optional.of("/audio/about/" + aLocale + "/" + file);
    }

    // ---------------------------------------------------------------- loading

    private void refreshIfStale() {
        final long now = System.currentTimeMillis();
        if (now - lastCheckedAt < STALE_AFTER_MS) return;
        lastCheckedAt = now;
        try {
            if (!Files.isReadable(manifest)) {
                if (loadedFromMtime != -1L) {
                    LOG.warn("about-audio manifest disappeared at {} — reporting no audio", manifest);
                    clear();
                }
                return;
            }
            final long mtime = Files.getLastModifiedTime(manifest).toMillis();
            if (mtime == loadedFromMtime) return;
            reload(mtime);
        } catch (final IOException | RuntimeException e) {
            LOG.warn("about-audio manifest could not be refreshed ({}): {}", manifest, e.toString());
        }
    }

    private void reload(final long aMtime) throws IOException {
        final JsonNode root = mapper.readTree(manifest.toFile());
        final JsonNode secs = root == null ? null : root.get("sections");
        if (secs == null || !secs.isObject()) {
            LOG.warn("about-audio manifest has no sections object — reporting no audio");
            clear();
            loadedFromMtime = aMtime;
            return;
        }
        final Map<String, Map<String, String>> newSections = new TreeMap<>();
        secs.fields().forEachRemaining(localeEntry -> {
            final String locale = localeEntry.getKey();
            final JsonNode bySection = localeEntry.getValue();
            if (bySection == null || !bySection.isObject()) return;
            final Map<String, String> files = new TreeMap<>();
            bySection.fields().forEachRemaining(sectionEntry -> {
                final JsonNode file = sectionEntry.getValue().get("file");
                if (file != null && !file.asText().isEmpty()) {
                    files.put(sectionEntry.getKey(), file.asText());
                }
            });
            if (!files.isEmpty()) {
                newSections.put(locale, Map.copyOf(files));
            }
        });
        sections = Map.copyOf(newSections);
        loadedFromMtime = aMtime;
        LOG.info("about-audio manifest loaded: {} locale(s), {} section(s)",
                newSections.size(),
                newSections.values().stream().mapToInt(Map::size).sum());
    }

    private void clear() {
        sections = Map.of();
        loadedFromMtime = -1L;
    }
}
