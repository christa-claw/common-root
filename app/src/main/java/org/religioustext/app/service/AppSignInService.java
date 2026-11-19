// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.religioustext.app.service.ApiKeyService.CreatedKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The phone app's sign-in through the website: the OAuth "authorization code with PKCE"
 * shape (RFC 7636), cut down to the one client there is.
 *
 * <ol>
 *   <li>The app makes a secret {@code code_verifier}, and opens
 *       {@code /app/sign-in?code_challenge=BASE64URL(SHA-256(verifier))&state=…&redirect_uri=…}
 *       in a browser tab. The site's own login page runs first if the reader is signed out.</li>
 *   <li>The reader confirms; the page sends the browser to {@code redirect_uri?code=…&state=…},
 *       which the phone hands to the app.</li>
 *   <li>The app posts the code and the verifier to {@code POST /api/v1/auth/token} and gets an
 *       API key of the reader's own, labelled {@value ApiKeyService#APP_LABEL}.</li>
 * </ol>
 *
 * <p>Why PKCE: the code travels through the phone's link handling, where another app that
 * claims the same scheme could see it. Without the verifier, which never left the app, the
 * code is worth nothing. The password never reaches the app at all.
 *
 * <p>Codes live in memory for {@link #CODE_LIFETIME}, are used once, and are bound to the
 * challenge and the redirect address they were issued for. A restart forgets the pending
 * ones; the reader taps Sign in again.
 *
 * @author Christa Claw
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@Service
public class AppSignInService {

    /** How long a code waits to be exchanged; the app does it within a second or two. */
    static final Duration CODE_LIFETIME = Duration.ofMinutes(5);

    /** The addresses the installed apps listen on: the released app, and the test build. */
    static final List<String> APP_REDIRECTS = List.of("commonroot://auth", "commonroot-test://auth");

    /** Most pending codes kept at once, so a page reloaded in a loop cannot fill memory. */
    static final int MAX_PENDING = 10_000;

    private final ApiKeyService keys;
    private final Clock         clock;
    private final List<String>  devRedirects;
    private final SecureRandom  random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param anApiKeyService mints the key the code is exchanged for
     * @param theDevRedirects extra redirect addresses, comma-separated, for a developer's
     *                        machine (the app's web build on localhost); empty in production
     */
    @Autowired
    public AppSignInService(final ApiKeyService anApiKeyService,
                            @Value("${app.sign-in.dev-redirects:}") final String theDevRedirects) {
        this(anApiKeyService, Clock.systemUTC(), theDevRedirects);
    }

    /** For tests: a fixed clock. */
    AppSignInService(final ApiKeyService anApiKeyService, final Clock aClock, final String theDevRedirects) {
        this.keys  = anApiKeyService;
        this.clock = aClock;
        this.devRedirects = Arrays.stream(Objects.toString(theDevRedirects, "").split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** A code waiting for its exchange. */
    private record Pending(String userId, String challenge, String redirectUri, long expiresAt) { }

    /**
     * Whether a redirect address belongs to the app. Matched exactly: a prefix or pattern
     * match is how open redirects are made.
     *
     * @param aRedirectUri the address the sign-in page was asked to return to
     * @return {@code true} for the app's own addresses (and configured developer ones)
     */
    public boolean isAllowedRedirect(final String aRedirectUri) {
        return aRedirectUri != null && (APP_REDIRECTS.contains(aRedirectUri) || devRedirects.contains(aRedirectUri));
    }

    /**
     * Whether a string is a well-formed S256 challenge: 43 base64url characters, the
     * length of an unpadded SHA-256.
     *
     * @param aChallenge the {@code code_challenge} parameter
     * @return {@code true} if it can be one
     */
    public static boolean isChallenge(final String aChallenge) {
        return aChallenge != null && aChallenge.matches("[A-Za-z0-9_-]{43}");
    }

    /**
     * Issue a one-time code for a signed-in reader.
     *
     * @param aUserId      the reader's {@code usr-} id
     * @param aChallenge   the app's S256 challenge
     * @param aRedirectUri where the code goes; must pass {@link #isAllowedRedirect}
     * @return the code, 43 base64url characters
     * @throws IllegalArgumentException for a bad challenge or redirect address
     */
    public String issueCode(final String aUserId, final String aChallenge, final String aRedirectUri) {
        if (!isChallenge(aChallenge)) throw new IllegalArgumentException("bad code_challenge");
        if (!isAllowedRedirect(aRedirectUri)) throw new IllegalArgumentException("bad redirect_uri");
        final long now = clock.millis();
        pending.values().removeIf(p -> p.expiresAt() <= now);
        if (pending.size() >= MAX_PENDING) throw new IllegalStateException("too many pending sign-ins");
        final byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        final String code = base64Url(bytes);
        pending.put(code, new Pending(aUserId, aChallenge, aRedirectUri, now + CODE_LIFETIME.toMillis()));
        return code;
    }

    /**
     * Exchange a code for an API key. The code is spent whatever the outcome, so a wrong
     * verifier cannot be retried against it.
     *
     * @param aCode         the code from the redirect
     * @param aVerifier     the app's secret, whose S256 must equal the challenge
     * @param aRedirectUri  the address the code was issued for
     * @return the new key, or empty when the code is unknown, used, expired or does not match
     * @throws IllegalStateException if the account already holds the most keys and none is an app key
     */
    public Optional<CreatedKey> redeem(final String aCode, final String aVerifier, final String aRedirectUri) {
        if (aCode == null || aVerifier == null) return Optional.empty();
        final Pending p = pending.remove(aCode);
        if (p == null || p.expiresAt() <= clock.millis()) return Optional.empty();
        if (!p.redirectUri().equals(aRedirectUri)) return Optional.empty();
        // RFC 7636 §4.1: 43–128 unreserved characters
        if (!aVerifier.matches("[A-Za-z0-9._~-]{43,128}")) return Optional.empty();
        if (!MessageDigest.isEqual(s256(aVerifier).getBytes(StandardCharsets.US_ASCII),
                                   p.challenge().getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        return Optional.of(keys.createForApp(p.userId()));
    }

    /**
     * The S256 transform: BASE64URL(SHA-256(ASCII(verifier))), unpadded.
     *
     * @param aVerifier the code verifier
     * @return its challenge
     */
    static String s256(final String aVerifier) {
        try {
            return base64Url(MessageDigest.getInstance("SHA-256")
                .digest(aVerifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e); // cannot happen
        }
    }

    private static String base64Url(final byte[] theBytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(theBytes);
    }
}
