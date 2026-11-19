// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import jakarta.servlet.http.HttpServletRequest;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Who may read an edition's text through the passage routes, a passage at a
 * time. A public-domain edition: anyone with a key. A licensed edition: the
 * phone app, which is a reader of the platform as the website is, within a
 * daily allowance per key. Whole-edition downloads are not decided here; they
 * stay public domain only ({@link ApiTextController#redistributable}).
 *
 * <p>The app names itself with {@value #CLIENT_HEADER}{@code : }{@value #APP_CLIENT}.
 * That is a client's statement, not a lock — anyone can send it, as anyone can
 * open the website. What bounds it is volume: {@code api.app.licensed-reads-per-day}
 * passages of licensed text per key per UTC day, sized well above a person
 * reading and far below an edition (NIV is 1,189 chapters), so taking a whole
 * licensed edition through one key is a matter of days. Guest keys, which come
 * without an account, are themselves rationed per address by
 * {@link ApiGuestKeyController}. Proving the app is genuine would take device
 * attestation (Play Integrity, App Attest), not a secret shipped inside the app.
 *
 * <p>The day's counts live in memory, like the burst window in
 * {@link org.religioustext.app.service.ApiQuotaService}: one box, one JVM, and a
 * restart that forgives a day's reading is no gift to a scraper.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@Component
public class AppReadPolicy {

    /** The header the Common Root? phone app sends with every request. */
    public static final String CLIENT_HEADER = "X-CommonRoot-Client";

    /** The value of {@link #CLIENT_HEADER} the phone app sends. */
    public static final String APP_CLIENT = "app";

    private static final long DAY_SECONDS = 86_400L;

    /** Whether, and how, a request may read an edition's passages. */
    public enum Access {
        /** Public domain: anyone with a key. */
        OPEN,
        /** Licensed, read by the app within its allowance; one read was counted. */
        APP,
        /** Licensed, and the request is not the app's. */
        REFUSED,
        /** Licensed, the app's, and the key has read its day's allowance. */
        SPENT
    }

    private final Clock clock;
    private final int   perDay;

    /** Per-key licensed reads for the current UTC day: keyRowId -> (day, count). */
    private final Map<String, DayCount> days = new ConcurrentHashMap<>();

    /**
     * @param aClock  the clock the day turns on
     * @param aPerDay licensed passages one key may read in a UTC day
     */
    public AppReadPolicy(final Clock aClock,
                         @Value("${api.app.licensed-reads-per-day:300}") final int aPerDay) {
        this.clock  = aClock;
        this.perDay = aPerDay;
    }

    /**
     * Decides a read of one edition's passage, counting it when it is the app
     * reading a licensed edition. A refused or over-allowance read counts nothing.
     *
     * @param aRow     the edition
     * @param aRequest the request: its {@link #CLIENT_HEADER}, and the key the
     *                 auth filter resolved ({@link ApiAuthFilter#ATTR_KEY})
     * @return the decision
     */
    public Access access(final SourceRow aRow, final HttpServletRequest aRequest) {
        if (ApiTextController.redistributable(aRow)) return Access.OPEN;
        if (aRequest == null || !APP_CLIENT.equalsIgnoreCase(aRequest.getHeader(CLIENT_HEADER))) {
            return Access.REFUSED;
        }
        final Object key = aRequest.getAttribute(ApiAuthFilter.ATTR_KEY);
        if (!(key instanceof ResolvedKey resolved) || resolved.key() == null) return Access.REFUSED;

        final long today = today();
        final boolean[] counted = {false};
        days.compute(resolved.key().getId(), (k, d) -> {
            final DayCount current = d == null || d.day != today ? new DayCount(today, 0) : d;
            if (current.count >= perDay) return current;
            counted[0] = true;
            return new DayCount(today, current.count + 1);
        });
        return counted[0] ? Access.APP : Access.SPENT;
    }

    /**
     * The response for a read {@link #access} did not allow: 403 for a licensed
     * edition the request may not read, 429 with {@code Retry-After} until the
     * next UTC midnight once the day's allowance is spent.
     *
     * @param aRow    the edition
     * @param anAccess the decision, {@link Access#REFUSED} or {@link Access#SPENT}
     * @return the error response
     */
    public ResponseEntity<Object> refusal(final SourceRow aRow, final Access anAccess) {
        if (anAccess == Access.SPENT) {
            final long now = clock.instant().getEpochSecond();
            final long reset = (now / DAY_SECONDS + 1) * DAY_SECONDS;
            return ResponseEntity.status(429).contentType(MediaType.APPLICATION_JSON)
                .header("Retry-After", Long.toString(Math.max(1, reset - now)))
                .body(ApiError.of(429, "daily_reading_limit",
                    "This key has read its " + perDay + " passages of licensed editions for today ("
                    + aRow.abbreviation() + " is one). The allowance renews at midnight UTC; "
                    + "public-domain editions read on.", "texts"));
        }
        return ResponseEntity.status(403).contentType(MediaType.APPLICATION_JSON)
            .body(ApiError.of(403, "not_redistributable",
                aRow.abbreviation() + " is not public domain (" + aRow.license()
                + "). Licensed editions are readable on the site but are not served "
                + "through the API. See /api/v1/texts for downloadable editions.", "texts"));
    }

    /**
     * How long a response carrying an edition's text may be cached, and by whom.
     * A licensed edition's text goes to the app alone, so no shared cache may
     * keep it and hand it to someone else.
     *
     * @param aRow     the edition the text is from
     * @param anAmount how long
     * @param aUnit    in what unit
     * @return public caching for public-domain text, private otherwise
     */
    public static CacheControl cacheFor(final SourceRow aRow, final long anAmount, final TimeUnit aUnit) {
        final CacheControl c = CacheControl.maxAge(anAmount, aUnit);
        return ApiTextController.redistributable(aRow) ? c.cachePublic() : c.cachePrivate();
    }

    private long today() {
        final Instant now = clock.instant();
        return now.getEpochSecond() / DAY_SECONDS;
    }

    /** One key's licensed reads within one UTC day. */
    private record DayCount(long day, int count) { }
}
