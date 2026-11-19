// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * The Android APK currently offered on {@link AppDownloadView}: its version,
 * where it is served, its size and its SHA-256 checksum.
 *
 * <p>The APK itself is not in the jar or in git. It sits on the server beside
 * the chapter audio and Caddy serves it straight off disk at
 * {@code /downloads/android/<file>} (see {@code docker/Caddyfile}). This record
 * only describes it, read from {@code android-release.properties} on the
 * classpath, so publishing a new APK is: copy the file to the server, update
 * the properties, release the app. The checksum shown on the page is the one
 * in the properties, which is what lets a reader check that the file they got
 * is the one we published.
 *
 * @param version    the app's version name, e.g. {@code 1.0.0}
 * @param file       the APK's file name under {@value #BASE}
 * @param bytes      the APK's size in bytes
 * @param sha256     the APK's SHA-256 checksum, lower-case hex
 * @param minAndroid the oldest Android version it installs on, e.g. {@code 7.0}
 * @param released   the day it was published, ISO {@code yyyy-MM-dd}
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
public record AndroidRelease(String version, String file, long bytes, String sha256,
                             String minAndroid, String released) {

    /** Where Caddy serves the APKs from. */
    public static final String BASE = "/downloads/android/";

    private static final String RESOURCE = "/android-release.properties";

    /**
     * The release described on the classpath, if there is one with a file.
     *
     * @return the release, or empty when the properties are missing or name no file
     */
    public static Optional<AndroidRelease> current() {
        try (InputStream in = AndroidRelease.class.getResourceAsStream(RESOURCE)) {
            if (in == null) return Optional.empty();
            final Properties p = new Properties();
            p.load(in);
            final String file = p.getProperty("file", "").trim();
            if (file.isEmpty()) return Optional.empty();
            return Optional.of(new AndroidRelease(
                p.getProperty("version", "").trim(), file,
                Long.parseLong(p.getProperty("bytes", "0").trim()),
                p.getProperty("sha256", "").trim(),
                p.getProperty("minAndroid", "").trim(),
                p.getProperty("released", "").trim()));
        } catch (final IOException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** @return the address the APK is downloaded from */
    public String href() {
        return BASE + file;
    }

    /** @return the size in megabytes, rounded to the nearest whole one */
    public long megabytes() {
        return Math.round(bytes / 1_048_576.0);
    }
}
