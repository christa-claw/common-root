// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import jakarta.servlet.http.HttpServletRequest;
import org.religioustext.app.service.ApiKeyService;
import org.religioustext.app.service.ApiKeyService.CreatedKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code POST /api/v1/guest-keys} — a read-only key with no account, for the phone
 * app's first launch (docs/api-spec.md §2.1).
 *
 * <p>Why not one key built into the app: anyone can lift it from the APK, every reader
 * would share its quota, and revoking it would break every install at once. A guest key
 * per install keeps each reader's quota their own and lets one bad key be revoked alone.
 *
 * <p>This is the one {@code /api} write that needs no key, so it carries its own limits:
 * at most {@code api.guest.per-address-per-hour} keys an hour for one address, and
 * {@code api.guest.per-hour} for everyone together, so a crowd of addresses cannot fill
 * the table either. Both counters are in memory and empty on restart, like the burst
 * limit; a restart costs at most one more hour's allowance.
 *
 * <p>Behind Caddy every request arrives from the proxy, so the reader's address is the
 * last {@code X-Forwarded-For} entry — the one Caddy itself appended — and only when the
 * request came from a private or loopback address, i.e. through the proxy. A header sent
 * straight to the app from outside is ignored, so it cannot buy a fresh allowance.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@RestController
public class ApiGuestKeyController {

    private final ApiKeyService keys;
    private final Clock         clock;
    private final int           perAddressPerHour;
    private final int           perHour;

    /** Keys issued this hour, per address; the hour is the map's key prefix. */
    private final Map<String, Integer> byAddress = new ConcurrentHashMap<>();
    /** Keys issued this hour by everyone, keyed by the hour. */
    private final Map<Long, Integer>   byHour    = new ConcurrentHashMap<>();

    @Autowired
    public ApiGuestKeyController(
             final ApiKeyService anApiKeyService
            , @Value("${api.guest.per-address-per-hour:5}") final int aPerAddressPerHour
            , @Value("${api.guest.per-hour:300}") final int aPerHour) {
        this(anApiKeyService, Clock.systemUTC(), aPerAddressPerHour, aPerHour);
    }

    /** For tests: a fixed clock. */
    ApiGuestKeyController(final ApiKeyService anApiKeyService, final Clock aClock,
                          final int aPerAddressPerHour, final int aPerHour) {
        this.keys              = anApiKeyService;
        this.clock             = aClock;
        this.perAddressPerHour = aPerAddressPerHour;
        this.perHour           = aPerHour;
    }

    /**
     * Issue a guest key. The body carries the only copy of the credential; the app
     * stores it on the device and the server keeps just its hash.
     *
     * @param aRequest the request, for the reader's address
     * @return 201 with {@code key}, {@code keyId} and {@code readOnly: true}; 429 past a limit
     */
    @PostMapping(value = ApiAuthFilter.GUEST_KEYS_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> issue(final HttpServletRequest aRequest) {
        final long hour = clock.millis() / 3_600_000L;
        if (!take(hour, clientAddress(aRequest))) {
            final long retryAfter = (hour + 1) * 3_600L - clock.millis() / 1_000L;
            return ResponseEntity.status(429)
                .header("Retry-After", Long.toString(retryAfter))
                .cacheControl(CacheControl.noStore())
                .body(ApiError.of(429, "guest_keys_exhausted",
                    "Too many guest keys asked for; try again after the Retry-After seconds.", "auth"));
        }
        final CreatedKey created = keys.createGuest();
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", created.key());
        body.put("keyId", created.record().getKeyId());
        body.put("readOnly", true);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Count one key against both hourly limits, or refuse without counting.
     *
     * @param anHour  the current hour since the epoch
     * @param anAddress the reader's address
     * @return {@code true} if the key may be issued
     */
    private synchronized boolean take(final long anHour, final String anAddress) {
        // last hour's counts are of no further use; the maps stay as small as one hour's traffic
        byHour.keySet().removeIf(h -> h != anHour);
        final String prefix = anHour + "|";
        byAddress.keySet().removeIf(k -> !k.startsWith(prefix));

        final String slot = prefix + anAddress;
        if (byHour.getOrDefault(anHour, 0) >= perHour) return false;
        if (byAddress.getOrDefault(slot, 0) >= perAddressPerHour) return false;
        byHour.merge(anHour, 1, Integer::sum);
        byAddress.merge(slot, 1, Integer::sum);
        return true;
    }

    /**
     * The reader's address: Caddy's last {@code X-Forwarded-For} entry when the request
     * came through the proxy, otherwise the connection's own address.
     *
     * @param aRequest the request
     * @return an address string, never {@code null}
     */
    static String clientAddress(final HttpServletRequest aRequest) {
        final String remote = String.valueOf(aRequest.getRemoteAddr());
        final String forwarded = aRequest.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isInternal(remote)) return remote;
        final String[] hops = forwarded.split(",");
        final String last = hops[hops.length - 1].trim();
        return last.isEmpty() ? remote : last;
    }

    /**
     * Whether an address is loopback or private — where the proxy talks to us from.
     * Read from the literal alone: {@code InetAddress.getByName} would look a name up,
     * and the address here is always a literal anyway.
     *
     * @param anAddress the connection's address, as the servlet container gives it
     * @return {@code true} for 127/8, 10/8, 172.16/12, 192.168/16, ::1 and fc00::/7
     */
    static boolean isInternal(final String anAddress) {
        final String a = anAddress.toLowerCase(Locale.ROOT);
        if (a.startsWith("127.") || a.startsWith("10.") || a.startsWith("192.168.")) return true;
        if (a.startsWith("172.")) {
            final String[] parts = a.split("\\.");
            try {
                final int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            } catch (final RuntimeException e) {
                return false;
            }
        }
        return a.equals("::1") || a.equals("0:0:0:0:0:0:0:1") || a.startsWith("fc") || a.startsWith("fd");
    }
}
