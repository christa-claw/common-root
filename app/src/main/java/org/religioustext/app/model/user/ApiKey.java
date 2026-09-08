package org.religioustext.app.model.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

/**
 * One API key on one account (V18; docs/api-spec.md §2).
 *
 * <p>The credential itself ({@code crk_} + 40 base62 characters) is never stored:
 * {@link #keyHash} holds its SHA-256 hex and {@link #keyId} the first eight
 * characters after the prefix, kept in plaintext purely so the account page can
 * say <em>which</em> key this row is ("crk_a81f02xx…"). Several live keys per
 * account is deliberate — rotation is create-new, move traffic, revoke-old,
 * never downtime.
 *
 * <p>A key is a credential for the account and nothing more: requests made with
 * it carry exactly the account's privileges. It raises capacity (a quota),
 * never entitlement — the download licence gate knows nothing about keys.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @Column(length = 40)
    private String id;

    /** Owning account ({@code usr-} id). A soft-typed reference like the accessor ids. */
    @Column(name = "user_id", nullable = false, length = 40)
    private String userId;

    /** First 8 characters after {@code crk_}, plaintext, for display only. */
    @Column(name = "key_id", nullable = false, unique = true, length = 8)
    private String keyId;

    /** SHA-256 hex of the full credential. The only form the key exists in at rest. */
    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(length = 64)
    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /** Non-null means revoked; revoked keys stay as audit rows and never authenticate. */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.API_KEY);
        createdAt = LocalDateTime.now();
    }

    public String        getId()         { return id; }
    public String        getUserId()     { return userId; }
    public String        getKeyId()      { return keyId; }
    public String        getKeyHash()    { return keyHash; }
    public String        getLabel()      { return label; }
    public LocalDateTime getCreatedAt()  { return createdAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public LocalDateTime getRevokedAt()  { return revokedAt; }

    public boolean isRevoked() { return revokedAt != null; }

    public void setUserId(final String aUserId)              { this.userId = aUserId; }
    public void setKeyId(final String aKeyId)                { this.keyId = aKeyId; }
    public void setKeyHash(final String aKeyHash)            { this.keyHash = aKeyHash; }
    public void setLabel(final String aLabel)                { this.label = aLabel; }
    public void setLastUsedAt(final LocalDateTime aMoment)   { this.lastUsedAt = aMoment; }
    public void setRevokedAt(final LocalDateTime aMoment)    { this.revokedAt = aMoment; }
}
