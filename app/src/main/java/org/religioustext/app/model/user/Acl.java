// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An access-control list: a named, reusable set of {@link Ace} entries an object points at
 * (docs/access-control.md §2, §6). The first concrete ACL is the per-channel
 * {@code channel-comments:<channelUserId>}, which every comment under that channel inherits.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Entity
@Table(name = "acls")
public class Acl {

    @Id
    @Column(length = 40)
    private String id;

    /** MACHINE KEY / slug — unique, stable, used by find-or-create and direct edits
     *  ({@code channel-comments:<id>}, {@code comment:<id>}, {@code system-default}). Not for display. */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Human-readable name/description — for named/shared ACLs edited on their own. NULL for
     *  custom per-comment instance ACLs (they don't need one). Doubles as the description. */
    @Column(length = 200)
    private String label;

    /** TRUE = a per-object CUSTOM (instance) ACL — a comment's copy-on-write copy. The admin
     *  "named ACLs" list filters these out. */
    @Column(name = "is_custom", nullable = false)
    private boolean custom = false;

    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    /** Owner — an ACCESSOR (user OR group). Soft ref (prefix discriminates), no cross-table FK. */
    @Column(name = "owner_accessor_id", length = 40)
    private String ownerAccessorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "acl", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<Ace> entries = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.ACL);
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public String        getId()          { return id; }
    public String        getName()        { return name; }
    public String        getLabel()       { return label; }
    public boolean       isCustom()       { return custom; }
    public boolean       isSystem()       { return system; }
    public String        getOwnerAccessorId() { return ownerAccessorId; }
    public List<Ace>     getEntries()     { return entries; }

    public void setName(final String aName)        { this.name = aName; }
    public void setLabel(final String aLabel)       { this.label = aLabel; }
    public void setCustom(final boolean aCustom)     { this.custom = aCustom; }
    public void setSystem(final boolean aSystem)     { this.system = aSystem; }
    public void setOwnerAccessorId(final String anOwnerAccessorId) { this.ownerAccessorId = anOwnerAccessorId; }
}
