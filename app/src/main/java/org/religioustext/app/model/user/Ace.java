package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

/**
 * One access-control entry: {@code accessor + entry-type + permission} (docs/access-control.md
 * §1–2). The accessor is a user, a group, a role, or a special ({@code world} = any
 * authenticated principal, {@code owner} = the object's owner). {@code accessorId} holds a
 * user/group id or a {@link Role} name; NULL for world/owner.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Entity
@Table(name = "aces")
public class Ace {

    /** What the entry names. {@code world} = everyone incl. anonymous (dm_world);
     *  {@code users} = any signed-in principal; {@code owner} = the object's owner. */
    public enum AccessorKind { user, users, group, role, world, owner }

    /** permit = grant; restriction = cap; required = AND-gate (must be a member). */
    public enum EntryType { permit, restriction, required }

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "acl_id", nullable = false)
    private Acl acl;

    @Enumerated(EnumType.STRING)
    @Column(name = "accessor_kind", nullable = false)
    private AccessorKind accessorKind;

    @Column(name = "accessor_id", length = 100)
    private String accessorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType entryType = EntryType.permit;

    @Enumerated(EnumType.STRING)
    @Column(name = "basic_level", nullable = false)
    private BasicLevel basicLevel;

    /** Comma-set of additive extended perms, e.g. "moderate,change_acl". Nullable. */
    @Column(name = "ext_perms", length = 255)
    private String extPerms;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.ACE);
    }

    public String       getId()           { return id; }
    public Acl          getAcl()          { return acl; }
    public AccessorKind getAccessorKind() { return accessorKind; }
    public String       getAccessorId()   { return accessorId; }
    public EntryType    getEntryType()    { return entryType; }
    public BasicLevel   getBasicLevel()   { return basicLevel; }
    public String       getExtPerms()     { return extPerms; }
    public int          getSortOrder()    { return sortOrder; }

    public void setAcl(final Acl anAcl)                 { this.acl = anAcl; }
    public void setAccessorKind(final AccessorKind anAccessorKind) { this.accessorKind = anAccessorKind; }
    public void setAccessorId(final String anAccessorId)       { this.accessorId = anAccessorId; }
    public void setEntryType(final EntryType anEntryType)     { this.entryType = anEntryType; }
    public void setBasicLevel(final BasicLevel aBasicLevel)   { this.basicLevel = aBasicLevel; }
    public void setExtPerms(final String anExtPerms)         { this.extPerms = anExtPerms; }
    public void setSortOrder(final int aSortOrder)           { this.sortOrder = aSortOrder; }
}
