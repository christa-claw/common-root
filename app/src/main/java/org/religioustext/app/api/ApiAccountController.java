// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import jakarta.servlet.http.HttpServletRequest;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.model.user.UserPreferences;
import org.religioustext.app.service.ApiKeyService;
import org.religioustext.app.service.ApiKeyService.CreatedKey;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.AppSignInService;
import org.religioustext.app.service.CommentQueryService;
import org.religioustext.app.service.UserPreferencesService;
import org.religioustext.app.service.UserService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The phone app's account routes: finishing a sign-in, signing out, who the key belongs
 * to, and the preferences the app shares with the website.
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/token} — exchange the code from the sign-in page for a key
 *       ({@link AppSignInService}). Needs no key; the code is the credential.</li>
 *   <li>{@code DELETE /api/v1/auth/key} — revoke the key the request was made with.</li>
 *   <li>{@code GET}/{@code PUT /api/v1/me} — the account's name, and changing its display
 *       name as the website's profile page does.</li>
 *   <li>{@code GET}/{@code PUT /api/v1/me/preferences} — the shared subset of
 *       {@link UserPreferences}. A {@code PUT} changes only the fields it carries.</li>
 * </ul>
 *
 * <p>The shared subset is what means the same on both: the interface language, the default
 * edition, the comment and cross-reference markers, the muted voices, and "continue where I
 * left off" with its position. The position is stored as the website stores it, a reader
 * link's query string; the API shows and takes only its first column, as {@code src} and
 * {@code ref}, which is all a phone screen shows.
 *
 * @author Christa Claw
 * @version 1.0.0-SNAPSHOT
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/v1")
public class ApiAccountController {

    private static final Pattern SRC = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9]{1,12}(\\.[0-9]{1,4}){0,2}");

    private final AppSignInService       signIn;
    private final ApiKeyService          keys;
    private final UserPreferencesService prefs;
    private final UserService            users;

    /** The longest display name the account row holds ({@code users.display_name}). */
    static final int MAX_NAME = 100;

    /**
     * @param anAppSignInService      checks and spends sign-in codes
     * @param anApiKeyService         revokes a key on sign-out
     * @param aUserPreferencesService the website's own preferences store
     * @param aUserService            changes the display name, as the profile page does
     */
    public ApiAccountController(final AppSignInService anAppSignInService,
                                final ApiKeyService anApiKeyService,
                                final UserPreferencesService aUserPreferencesService,
                                final UserService aUserService) {
        this.signIn = anAppSignInService;
        this.keys   = anApiKeyService;
        this.prefs  = aUserPreferencesService;
        this.users  = aUserService;
    }

    /**
     * A change to the account.
     *
     * @param displayName the name shown to others; empty shows the email address instead
     */
    public record MeIn(String displayName) { }

    /**
     * The exchange request.
     *
     * @param code         the code the sign-in page sent to the app
     * @param codeVerifier the app's secret behind the challenge
     * @param redirectUri  the address the code was sent to
     */
    public record TokenIn(String code, String codeVerifier, String redirectUri) { }

    /**
     * The shared preferences. In a {@code PUT}, a field left out (null) is left as it is.
     *
     * @param uiLanguage     interface language tag; empty string clears it
     * @param defaultSource  default edition token; empty string clears it
     * @param commentMarkers comment counts beside verses
     * @param xrefMarkers    cross-reference markers beside verses
     * @param mutedVoices    commenters hidden by default
     * @param resume         whether to open where the reader stopped
     * @param position       where the reader stopped; ignored while resume is off
     */
    public record PrefsIn(String uiLanguage, String defaultSource, Boolean commentMarkers,
                          Boolean xrefMarkers, List<String> mutedVoices, Boolean resume,
                          Position position) { }

    /**
     * A reading position: an edition and a reference in link format.
     *
     * @param src the edition token
     * @param ref the reference, e.g. {@code JHN.3} or {@code JHN.3.16}
     */
    public record Position(String src, String ref) { }

    /**
     * {@code POST /api/v1/auth/token} — the app's half of the sign-in.
     *
     * @param aToken the code, verifier and redirect address
     * @return 201 with {@code key}, {@code keyId} and {@code name}; 400 for a code that does
     *         not check out; 409 when the account holds the most keys allowed
     */
    @PostMapping(value = "/auth/token", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> token(@RequestBody final TokenIn aToken) {
        final Optional<CreatedKey> created;
        try {
            created = signIn.redeem(aToken.code(), aToken.codeVerifier(), aToken.redirectUri());
        } catch (final IllegalStateException e) {
            return ResponseEntity.status(409).cacheControl(CacheControl.noStore())
                .body(ApiError.of(409, "too_many_keys",
                    "This account already holds " + ApiKeyService.MAX_LIVE_KEYS
                    + " API keys. Revoke one on the website's API keys page, then sign in again.", "auth"));
        }
        if (created.isEmpty()) {
            return ResponseEntity.status(400).cacheControl(CacheControl.noStore())
                .body(ApiError.of(400, "invalid_grant",
                    "This sign-in has expired or was already used. Sign in again.", "auth"));
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", created.get().key());
        body.put("keyId", created.get().record().getKeyId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * {@code DELETE /api/v1/auth/key} — sign out: the key this request was made with stops
     * working. A guest key cannot get here (the auth filter refuses guest deletes).
     *
     * @param aRequest carries the resolved key
     * @return 204
     */
    @DeleteMapping("/auth/key")
    public ResponseEntity<Void> signOut(final HttpServletRequest aRequest) {
        final ResolvedKey resolved = resolved(aRequest);
        if (resolved.owner() != null) keys.revoke(resolved.owner().getId(), resolved.key().getId());
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code GET /api/v1/me} — whose key this is, for the app's "Signed in as …".
     *
     * @param aRequest carries the resolved key
     * @return 200 with {@code name} (the display name, or the email address when there is
     *         none) and {@code displayName} (as set, empty when there is none); 403 for a
     *         guest key
     */
    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> me(final HttpServletRequest aRequest) {
        final ResolvedKey resolved = resolved(aRequest);
        if (resolved.owner() == null) return guestRefused();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(meView(resolved.owner().getDisplayName(), resolved.owner().getEmail()));
    }

    /**
     * {@code PUT /api/v1/me} — change the display name, as the website's profile page does.
     *
     * @param aRequest carries the resolved key
     * @param aChange  the new display name; blank removes it
     * @return 200 with the account as {@link #me} shows it; 400 for a name longer than
     *         {@value #MAX_NAME} characters or a body without one; 403 for a guest key
     */
    @PutMapping(value = "/me", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> updateMe(final HttpServletRequest aRequest,
                                           @RequestBody final MeIn aChange) {
        final ResolvedKey resolved = resolved(aRequest);
        if (resolved.owner() == null) return guestRefused();
        final String problem = problemWith(aChange);
        if (problem != null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "invalid_name", problem, "account"));
        }
        final String email = resolved.owner().getEmail();
        users.updateDisplayName(email, aChange.displayName());
        final String stored = users.findByEmail(email).map(u -> u.getDisplayName()).orElse(null);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(meView(stored, email));
    }

    /**
     * Why a change to the account cannot be stored, or {@code null} when it can.
     *
     * @param aChange the body
     * @return a sentence for the client, or {@code null}
     */
    static String problemWith(final MeIn aChange) {
        if (aChange == null || aChange.displayName() == null) return "displayName is missing.";
        if (aChange.displayName().trim().length() > MAX_NAME) {
            return "displayName holds at most " + MAX_NAME + " characters.";
        }
        return null;
    }

    /**
     * The account as {@code /me} shows it.
     *
     * @param aDisplayName the stored display name, or {@code null}
     * @param anEmail      the account's email address, shown when there is no display name
     * @return {@code {name, displayName}}
     */
    static Map<String, Object> meView(final String aDisplayName, final String anEmail) {
        final boolean none = aDisplayName == null || aDisplayName.isBlank();
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", none ? anEmail : aDisplayName);
        body.put("displayName", none ? "" : aDisplayName);
        return body;
    }

    /**
     * {@code GET /api/v1/me/preferences}.
     *
     * @param aRequest carries the resolved key
     * @return 200 with the shared preferences and their {@code updatedAt}; 403 for a guest key
     */
    @GetMapping(value = "/me/preferences", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> preferences(final HttpServletRequest aRequest) {
        final ResolvedKey resolved = resolved(aRequest);
        if (resolved.owner() == null) return guestRefused();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(view(prefs.find(resolved.owner().getEmail()).orElse(null)));
    }

    /**
     * {@code PUT /api/v1/me/preferences} — change the fields the body carries.
     *
     * @param aRequest carries the resolved key
     * @param aPrefs   the fields to change
     * @return 200 with the preferences as now stored; 400 for a value the website would not
     *         accept; 403 for a guest key
     */
    @PutMapping(value = "/me/preferences", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> updatePreferences(final HttpServletRequest aRequest,
                                                    @RequestBody final PrefsIn aPrefs) {
        final ResolvedKey resolved = resolved(aRequest);
        if (resolved.owner() == null) return guestRefused();
        final String problem = problemWith(aPrefs);
        if (problem != null) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "invalid_preferences", problem, "account"));
        }
        final UserPreferences saved = prefs.save(resolved.owner().getEmail(), p -> apply(p, aPrefs));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(saved));
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    /**
     * Why a preferences body cannot be stored, or {@code null} when it can.
     *
     * @param aPrefs the body
     * @return a sentence for the client, or {@code null}
     */
    static String problemWith(final PrefsIn aPrefs) {
        if (aPrefs.uiLanguage() != null && !aPrefs.uiLanguage().isEmpty()
                && LocaleUtil.LOCALES.stream().noneMatch(l -> l.toLanguageTag().equals(aPrefs.uiLanguage()))) {
            return "uiLanguage is not one of the website's languages.";
        }
        if (aPrefs.defaultSource() != null && !aPrefs.defaultSource().isEmpty()
                && !SRC.matcher(aPrefs.defaultSource()).matches()) {
            return "defaultSource is not an edition token.";
        }
        if (aPrefs.mutedVoices() != null && aPrefs.mutedVoices().size() > 200) {
            return "mutedVoices holds at most 200 names.";
        }
        final Position pos = aPrefs.position();
        if (pos != null && (pos.src() == null || !SRC.matcher(pos.src()).matches()
                || pos.ref() == null || !REF.matcher(pos.ref()).matches())) {
            return "position needs an edition token (src) and a reference such as JHN.3 (ref).";
        }
        return null;
    }

    /**
     * Apply a {@code PUT} body to the stored row: only the fields it carries.
     *
     * @param aRow   the managed preferences row
     * @param aPrefs the checked body
     */
    static void apply(final UserPreferences aRow, final PrefsIn aPrefs) {
        if (aPrefs.uiLanguage() != null) aRow.setUiLanguage(blankToNull(aPrefs.uiLanguage()));
        if (aPrefs.defaultSource() != null) aRow.setDefaultSource(blankToNull(aPrefs.defaultSource()));
        if (aPrefs.commentMarkers() != null) aRow.setShowCommentMarkers(aPrefs.commentMarkers());
        if (aPrefs.xrefMarkers() != null) aRow.setShowXrefMarkers(aPrefs.xrefMarkers());
        if (aPrefs.mutedVoices() != null) aRow.setMutedVoices(CommentQueryService.formatMuted(aPrefs.mutedVoices()));
        if (aPrefs.resume() != null) {
            aRow.setResumeEnabled(aPrefs.resume());
            // the website forgets the position when resume is switched off; so does the app
            if (!aPrefs.resume()) aRow.setLastPosition(null);
        }
        if (aPrefs.position() != null && aRow.isResumeEnabled()) {
            aRow.setLastPosition("c1.src=" + enc(aPrefs.position().src()) + "&c1.ref=" + enc(aPrefs.position().ref()));
        }
    }

    /**
     * The shared preferences as the API shows them; a reader who never saved any gets the
     * website's defaults.
     *
     * @param aRow the stored row, or {@code null}
     * @return the response body
     */
    static Map<String, Object> view(final UserPreferences aRow) {
        final UserPreferences p = aRow != null ? aRow : new UserPreferences();
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("uiLanguage", p.getUiLanguage());
        body.put("defaultSource", p.getDefaultSource());
        body.put("commentMarkers", p.isShowCommentMarkers());
        body.put("xrefMarkers", p.isShowXrefMarkers());
        body.put("mutedVoices", new ArrayList<>(CommentQueryService.parseMuted(p.getMutedVoices())));
        body.put("resume", p.isResumeEnabled());
        body.put("position", p.isResumeEnabled() ? position(p.getLastPosition()) : null);
        body.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        return body;
    }

    /**
     * The first column of a stored reader-link query string.
     *
     * @param aQuery e.g. {@code c1.src=kjv&c1.ref=JHN.3.16&c2.src=q-en}
     * @return {@code {src, ref}}, or {@code null} when there is no first column
     */
    static Map<String, String> position(final String aQuery) {
        if (aQuery == null || aQuery.isBlank()) return null;
        String src = null;
        String ref = null;
        for (final String kv : aQuery.split("&")) {
            final int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            final String k;
            final String v;
            try {
                k = URLDecoder.decode(kv.substring(0, eq), StandardCharsets.UTF_8);
                v = URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException e) {
                continue;
            }
            if (k.equals("c1.src")) src = v.toLowerCase(Locale.ROOT);
            else if (k.equals("c1.ref")) ref = v;
        }
        if (src == null) return null;
        final Map<String, String> out = new LinkedHashMap<>();
        out.put("src", src);
        out.put("ref", ref);
        return out;
    }

    private static String enc(final String aValue) {
        return URLEncoder.encode(aValue, StandardCharsets.UTF_8);
    }

    private static String blankToNull(final String aValue) {
        return aValue.isBlank() ? null : aValue;
    }

    private static ResolvedKey resolved(final HttpServletRequest aRequest) {
        return (ResolvedKey) aRequest.getAttribute(ApiAuthFilter.ATTR_KEY);
    }

    private static ResponseEntity<Object> guestRefused() {
        return ResponseEntity.status(403).body(ApiError.of(403, "sign_in_required",
            "A guest key has no account. Sign in to share preferences with the website.", "auth"));
    }
}
