package org.religioustext.app.service;

import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.ApiKeyRepository;
import org.religioustext.app.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issues, revokes and resolves API keys (docs/api-spec.md §2).
 *
 * <p>A credential is {@code crk_} + 40 base62 characters from
 * {@link SecureRandom} — the {@code crk_} prefix makes a leaked key greppable
 * and secret-scanner-detectable. The plaintext exists exactly once, in the
 * return value of {@link #create}; at rest there is only the SHA-256 hex
 * ({@code key_hash}) and the first eight characters after the prefix
 * ({@code key_id}), kept for display so the account page can name the key.
 *
 * <p>Any active account may hold keys (Consumer tier suffices — the ladder is
 * not consulted here because a key carries exactly the account's own
 * privileges, no more), capped at {@value #MAX_LIVE_KEYS} live keys so
 * rotation is possible and hoarding is not.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    /** The recognisable, greppable credential prefix. */
    public static final String PREFIX = "crk_";

    /** Random characters after the prefix. */
    static final int RANDOM_LENGTH = 40;

    /** Of those, the leading characters kept in plaintext as the display key id. */
    static final int KEY_ID_LENGTH = 8;

    /** Live (unrevoked) keys an account may hold at once. */
    public static final int MAX_LIVE_KEYS = 5;

    private static final String BASE62 =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final ApiKeyRepository apiKeys;
    private final UserRepository   users;
    private final SecureRandom     random = new SecureRandom();

    public ApiKeyService(
             final ApiKeyRepository anApiKeyRepository
            , final UserRepository  aUserRepository) {
        this.apiKeys = anApiKeyRepository;
        this.users   = aUserRepository;
    }

    /**
     * The one moment a credential exists in plaintext.
     *
     * @param key    the full credential to show the user once and never again
     * @param record the stored row (hash, display id, label)
     */
    public record CreatedKey(String key, ApiKey record) { }

    /**
     * Issue a new key for an account. The returned plaintext is shown once;
     * only its hash is stored.
     *
     * @param aUserId the owning account's {@code usr-} id
     * @param aLabel  optional free-text label ("CI", "laptop"), may be {@code null}
     * @return the plaintext credential and its stored row
     * @throws IllegalStateException if the account already holds {@value #MAX_LIVE_KEYS} live keys
     */
    @Transactional
    public CreatedKey create(final String aUserId, final String aLabel) {
        if (apiKeys.countByUserIdAndRevokedAtIsNull(aUserId) >= MAX_LIVE_KEYS) {
            throw new IllegalStateException(
                "Account already holds " + MAX_LIVE_KEYS + " live API keys; revoke one first.");
        }
        final String plaintext = generate();
        final ApiKey row = new ApiKey();
        row.setUserId(aUserId);
        row.setKeyId(plaintext.substring(PREFIX.length(), PREFIX.length() + KEY_ID_LENGTH));
        row.setKeyHash(sha256Hex(plaintext));
        row.setLabel(aLabel == null || aLabel.isBlank() ? null : aLabel.trim());
        final ApiKey saved = apiKeys.save(row);
        log.info("Issued API key {} for account {}", saved.getKeyId(), aUserId);
        return new CreatedKey(plaintext, saved);
    }

    /**
     * Revoke one of an account's keys. Idempotent; the row stays for audit and
     * the account listing, but the key never authenticates again.
     *
     * @param aUserId the calling account's {@code usr-} id — a key belonging to
     *                anyone else is not touched
     * @param aKeyRowId the {@code key-} row id to revoke
     * @return {@code true} if a key of this account was newly revoked
     */
    @Transactional
    public boolean revoke(final String aUserId, final String aKeyRowId) {
        final Optional<ApiKey> row = apiKeys.findById(aKeyRowId);
        if (row.isEmpty() || !row.get().getUserId().equals(aUserId)) return false;
        if (row.get().isRevoked()) return false;
        row.get().setRevokedAt(LocalDateTime.now());
        apiKeys.save(row.get());
        log.info("Revoked API key {} of account {}", row.get().getKeyId(), aUserId);
        return true;
    }

    /**
     * The account page listing: all of an account's keys, newest first.
     *
     * @param aUserId the account's {@code usr-} id
     * @return the keys, live and revoked
     */
    public List<ApiKey> listFor(final String aUserId) {
        return apiKeys.findByUserIdOrderByCreatedAtDesc(aUserId);
    }

    /**
     * The auth-filter resolution: presented credential to owning account.
     * Fails closed on everything — malformed, unknown, revoked, or an inactive
     * owner all come back empty and indistinguishable to the caller (the
     * distinctions are logged, not returned).
     *
     * @param aPresentedKey the raw credential from the Authorization header
     * @return the key row and its active owner, or empty
     */
    @Transactional
    public Optional<ResolvedKey> resolve(final String aPresentedKey) {
        if (aPresentedKey == null || !looksLikeKey(aPresentedKey)) return Optional.empty();
        final Optional<ApiKey> row = apiKeys.findByKeyHash(sha256Hex(aPresentedKey));
        if (row.isEmpty()) return Optional.empty();
        if (row.get().isRevoked()) {
            log.info("Rejected revoked API key {}", row.get().getKeyId());
            return Optional.empty();
        }
        final Optional<User> owner = users.findById(row.get().getUserId());
        if (owner.isEmpty() || !owner.get().isActive()) {
            log.info("Rejected API key {} of missing/inactive account", row.get().getKeyId());
            return Optional.empty();
        }
        row.get().setLastUsedAt(LocalDateTime.now());
        apiKeys.save(row.get());
        return Optional.of(new ResolvedKey(row.get(), owner.get()));
    }

    /** A successfully authenticated key and its owning account. */
    public record ResolvedKey(ApiKey key, User owner) { }

    /**
     * Whether a string has the shape of a credential — the cheap pre-hash gate.
     *
     * @param aCandidate the string to test
     * @return {@code true} for {@code crk_} + exactly {@value #RANDOM_LENGTH} base62 characters
     */
    static boolean looksLikeKey(final String aCandidate) {
        if (aCandidate == null || !aCandidate.startsWith(PREFIX)) return false;
        final String rest = aCandidate.substring(PREFIX.length());
        if (rest.length() != RANDOM_LENGTH) return false;
        for (int i = 0; i < rest.length(); i++) {
            if (BASE62.indexOf(rest.charAt(i)) < 0) return false;
        }
        return true;
    }

    /**
     * A fresh credential: {@code crk_} + {@value #RANDOM_LENGTH} base62 characters.
     *
     * @return the plaintext credential
     */
    String generate() {
        final StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            sb.append(BASE62.charAt(random.nextInt(BASE62.length())));
        }
        return sb.toString();
    }

    /**
     * SHA-256 of a credential, lowercase hex — the at-rest and lookup form.
     *
     * @param aValue the plaintext credential
     * @return 64 lowercase hex characters
     */
    static String sha256Hex(final String aValue) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(aValue.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e); // cannot happen
        }
    }
}
