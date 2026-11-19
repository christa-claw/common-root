// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.religioustext.app.service.ApiKeyService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.ApiQuotaService;
import org.religioustext.app.service.ApiQuotaService.Decision;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Authenticates and meters every {@code /api/**} request (docs/api-spec.md §2, §5).
 *
 * <p>Order of checks, cheapest first: path scope → the health, docs and
 * guest-key exemptions → the query-string rejection → Bearer parse → key
 * resolution → burst and monthly quota → the guest key's read-only rule. On success the resolved key and account ride the request as
 * attributes ({@link #ATTR_KEY}) for the controllers, and every response —
 * success or refusal — carries the rate-limit headers, because a client that
 * cannot see its ceiling retries in a loop and creates the load the quota
 * exists to prevent.
 *
 * <p>A key in the query string is rejected <em>even when valid</em>, with a
 * body saying why: query strings land in access logs and Referer headers, and
 * silently honouring one would teach the habit this rule exists to break.
 *
 * <p>Failed auth creates no usage rows: quotas are for users, not attackers —
 * attackers are Caddy's per-IP job.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    /** Where the phone app gets a guest key; it is the one write that needs no key. */
    static final String GUEST_KEYS_PATH = "/api/v1/guest-keys";

    /** Request attribute carrying the authenticated {@link ResolvedKey}. */
    public static final String ATTR_KEY = "org.religioustext.api.resolvedKey";

    static final String HEADER_LIMIT     = "X-RateLimit-Limit";
    static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_RESET     = "X-RateLimit-Reset";

    private final ApiKeyService   keys;
    private final ApiQuotaService quota;
    private final ObjectMapper    json;

    public ApiAuthFilter(
             final ApiKeyService   anApiKeyService
            , final ApiQuotaService anApiQuotaService
            , final ObjectMapper   anObjectMapper) {
        this.keys  = anApiKeyService;
        this.quota = anApiQuotaService;
        this.json  = anObjectMapper;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest aRequest) {
        final String path = aRequest.getRequestURI();
        if (!path.startsWith("/api/")) return true;
        return path.equals("/api/v1/health")    // no auth, no quota — the uptime signal
            || path.equals("/api/docs")         // the documentation must be readable without a key
            || path.equals(GUEST_KEYS_PATH);    // where a key comes from cannot need one
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest aRequest,
                                    final HttpServletResponse aResponse,
                                    final FilterChain aChain)
            throws ServletException, IOException {

        // A key in the query string is refused even when it would authenticate.
        if (hasKeyInQueryString(aRequest)) {
            refuse(aResponse, 401, "key_in_query_string",
                "API keys are accepted in the Authorization header only "
                + "(Authorization: Bearer crk_…). Query strings land in access logs "
                + "and Referer headers; treat this key as leaked and rotate it.",
                "auth");
            return;
        }

        final String presented = bearerToken(aRequest);
        if (presented == null) {
            refuse(aResponse, 401, "missing_key",
                "This endpoint needs an API key: Authorization: Bearer crk_…. "
                + "Keys are free — create one on your account page.", "auth");
            return;
        }

        final ResolvedKey resolved = keys.resolve(presented).orElse(null);
        if (resolved == null) {
            refuse(aResponse, 401, "invalid_key",
                "This API key is unknown or revoked.", "auth");
            return;
        }

        final Decision decision = quota.countRequest(resolved.key().getId());
        aResponse.setHeader(HEADER_LIMIT,     Long.toString(decision.limit()));
        aResponse.setHeader(HEADER_REMAINING, Long.toString(decision.remaining()));
        aResponse.setHeader(HEADER_RESET,     Long.toString(decision.resetEpoch()));
        if (!decision.allowed()) {
            aResponse.setHeader("Retry-After", Long.toString(decision.retryAfter()));
            refuse(aResponse, 429, "quota_exceeded",
                "Allowance used; retry after the reset the headers name.", "quotas");
            return;
        }

        if (resolved.isGuest() && !guestMay(aRequest)) {
            refuse(aResponse, 403, "sign_in_required",
                "A guest key reads only. Sign in and use your own key to write notes and "
                + "comments, dictate, or download whole texts.", "auth");
            return;
        }

        aRequest.setAttribute(ATTR_KEY, resolved);
        aChain.doFilter(aRequest, aResponse);
    }

    /**
     * What a guest key may do: read. Writes have no account to write as, and two
     * reads are kept back as well — dictation, which costs per minute, and whole-text
     * downloads, which a new guest key per download would let anyone take without limit.
     * Checking an attestation is a POST but only reads, so it is allowed.
     *
     * @param aRequest the request
     * @return {@code true} if a guest key may make it
     */
    static boolean guestMay(final HttpServletRequest aRequest) {
        final String path = aRequest.getRequestURI();
        return switch (aRequest.getMethod()) {
            case "GET", "HEAD" -> !path.endsWith("/download") && !path.startsWith("/api/v1/dictation");
            case "POST"        -> path.equals("/api/v1/verify");
            default            -> false;
        };
    }

    /**
     * The Bearer credential, or {@code null} when the header is absent or not
     * a Bearer scheme. A malformed header is indistinguishable from a missing
     * one on purpose — both are a 401, never a 500.
     *
     * @param aRequest the request
     * @return the raw token, or {@code null}
     */
    static String bearerToken(final HttpServletRequest aRequest) {
        final String header = aRequest.getHeader("Authorization");
        if (header == null) return null;
        final String trimmed = header.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        final String token = trimmed.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    /**
     * Whether any query parameter smells like a credential — the shape
     * ({@code crk_…}) anywhere in the query string, whatever the parameter name.
     *
     * @param aRequest the request
     * @return {@code true} if a key travelled in the URL
     */
    static boolean hasKeyInQueryString(final HttpServletRequest aRequest) {
        final String query = aRequest.getQueryString();
        return query != null && query.contains(ApiKeyService.PREFIX);
    }

    private void refuse(final HttpServletResponse aResponse, final int aStatus,
                        final String anError, final String aMessage,
                        final String anAnchor) throws IOException {
        aResponse.setStatus(aStatus);
        aResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
        aResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(aResponse.getWriter(),
                        ApiError.of(aStatus, anError, aMessage, anAnchor));
    }
}
