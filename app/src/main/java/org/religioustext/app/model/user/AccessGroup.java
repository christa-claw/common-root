// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

/**
 * An ACL-plane group — a security principal you grant access THROUGH (Documentum sense).
 * Members inherit whatever the group is granted in an {@link Ace}. The first use is the
 * per-channel member group {@code channel-members:<channelUserId>} (docs/access-control.md §4).
 *
 * Table is {@code access_groups} (not {@code groups}: GROUPS is reserved in MySQL 8, and
 * the bare name is left to the unrelated tradition feature in docs/groups.md).
 * Membership lives in {@link GroupMembership}.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Entity
@Table(name = "access_groups")
public class AccessGroup implements Accessor {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Human-facing display title (V15) — same idea as {@code acls.label}. NULL falls back to
     *  {@code name} (or the derived channel display name for channel member groups). */
    @Column(length = 100)
    private String label;

    /** What the group is for — shown in the admin accessors view. NULL when nobody wrote one. */
    @Column(length = 500)
    private String description;

    /** Seeded/reserved groups (e.g. per-channel member groups) — not user-deletable. */
    @Column(name = "is_system", nullable = false)
    private boolean system = true;

    /** Group admin / owner — an ACCESSOR (user OR group), NULL until claimed. Soft ref
     *  (no cross-table FK, since it may point at users or access_groups); prefix discriminates. */
    @Column(name = "owner_accessor_id", length = 40)
    private String ownerAccessorId;

    /** The org's DEFAULT ACL — objects owned by this org with no ACL of their own fall back
     *  to it (then to the system default). See docs/access-control.md §4. */
    @Column(name = "default_acl_id", length = 40)
    private String defaultAclId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.GROUP);
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    /** {@inheritDoc} */
    @Override public String getId()       { return id; }
    /** {@inheritDoc} Always {@link Ace.AccessorKind#group}. */
    @Override public Ace.AccessorKind accessorKind() { return Ace.AccessorKind.group; }
    public String        getName()        { return name; }
    public String        getLabel()       { return label; }
    public String        getDescription() { return description; }
    public boolean       isSystem()       { return system; }
    public String        getOwnerAccessorId() { return ownerAccessorId; }
    public String        getDefaultAclId() { return defaultAclId; }
    public LocalDateTime getCreatedAt()   { return createdAt; }
    public LocalDateTime getUpdatedAt()   { return updatedAt; }

    public void setName(final String aName)        { this.name = aName; }
    public void setLabel(final String aLabel)      { this.label = aLabel; }
    public void setDescription(final String aDescription) { this.description = aDescription; }
    public void setSystem(final boolean aSystem)     { this.system = aSystem; }
    public void setOwnerAccessorId(final String anOwnerAccessorId) { this.ownerAccessorId = anOwnerAccessorId; }
    public void setDefaultAclId(final String aDefaultAclId) { this.defaultAclId = aDefaultAclId; }
}
