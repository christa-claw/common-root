package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User implements Accessor {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "email_verification_token", length = 100)
    private String emailVerificationToken;

    // Password reset (V7): a one-time token + its expiry. Both NULL when no
    // reset is in progress; cleared once the password is changed.
    @Column(name = "password_reset_token", length = 100)
    private String passwordResetToken;

    @Column(name = "password_reset_expires")
    private LocalDateTime passwordResetExpires;

    @Column(name = "is_admin", nullable = false)
    private boolean admin = false;

    // System-wide role ladder (V11). Authoritative going forward; is_admin is kept
    // for one release as a read-through and dropped later. See docs/access-control.md.
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role = Role.consumer;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    // System-owned "channel" account (one per ingested channel) that owns the
    // auto-generated argument comments. Non-loginable; its comments are re-seeded
    // by DataSeeder every startup. A future "claim your channel" flow flips this
    // to false, turning it into a real login (V10).
    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.USER);
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    /** {@inheritDoc} */
    @Override public String getId()        { return id; }
    /** {@inheritDoc} Always {@link Ace.AccessorKind#user}. */
    @Override public Ace.AccessorKind accessorKind() { return Ace.AccessorKind.user; }
    public String        getEmail()        { return email; }
    public String        getPasswordHash() { return passwordHash; }
    public String        getDisplayName()  { return displayName; }
    public LocalDateTime getCreatedAt()    { return createdAt; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }
    public boolean       isVerified()      { return verified; }
    public boolean       isAdmin()         { return admin; }
    public boolean       isActive()        { return active; }

    public void setEmail(final String anEmail)        { this.email = anEmail; }
    public void setPasswordHash(final String aPasswordHash) { this.passwordHash = aPasswordHash; }
    public void setDisplayName(final String aDisplayName)  { this.displayName = aDisplayName; }
    public void setVerified(final boolean aVerified)              { this.verified = aVerified; }
    public void setAdmin(final boolean anAdmin)                  { this.admin = anAdmin; }
    public void setActive(final boolean anActive)                 { this.active = anActive; }
    public boolean       isSystem()        { return system; }
    public void setSystem(final boolean aSystem)                 { this.system = aSystem; }
    public Role          getRole()         { return role; }
    public void          setRole(final Role aRole)             { this.role = aRole; }
    public String getEmailVerificationToken()              { return emailVerificationToken; }
    public void setEmailVerificationToken(final String aToken)  { this.emailVerificationToken = aToken; }

    public String        getPasswordResetToken()                 { return passwordResetToken; }
    public void          setPasswordResetToken(final String aToken)   { this.passwordResetToken = aToken; }
    public LocalDateTime getPasswordResetExpires()               { return passwordResetExpires; }
    public void          setPasswordResetExpires(final LocalDateTime anExpiry) { this.passwordResetExpires = anExpiry; }
}
