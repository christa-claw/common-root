package org.religioustext.app.model.user;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * A member of an {@link AccessGroup}. The member is an ACCESSOR — a {@link User} OR another
 * {@link AccessGroup} (nesting). {@code memberKind} discriminates; {@code memberAccessorId}
 * is the member's typed id. Composite key (group_id, member_accessor_id).
 *
 * A group member (kind = group) makes this group a parent of that group, so a user in the
 * child is transitively a member of the parent (resolved in {@code GroupService}).
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Entity
@Table(name = "group_memberships")
@IdClass(GroupMembership.Key.class)
public class GroupMembership {

    @Id
    @Column(name = "group_id", length = 40)
    private String groupId;

    @Id
    @Column(name = "member_accessor_id", length = 40)
    private String memberAccessorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_kind", nullable = false)
    private Ace.AccessorKind memberKind = Ace.AccessorKind.user;

    protected GroupMembership() { }

    public GroupMembership(final String aGroupId, final String aMemberAccessorId,
                           final Ace.AccessorKind aMemberKind) {
        this.groupId = aGroupId;
        this.memberAccessorId = aMemberAccessorId;
        this.memberKind = aMemberKind;
    }

    public String getGroupId()          { return groupId; }
    public String getMemberAccessorId() { return memberAccessorId; }
    public Ace.AccessorKind getMemberKind() { return memberKind; }

    /** Composite primary key. */
    public static class Key implements Serializable {
        private String groupId;
        private String memberAccessorId;

        public Key() { }
        public Key(final String aGroupId, final String aMemberAccessorId) {
            this.groupId = aGroupId;
            this.memberAccessorId = aMemberAccessorId;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(final Object anObject) {
            if (this == anObject) return true;
            if (!(anObject instanceof Key k)) return false;
            return Objects.equals(groupId, k.groupId)
                && Objects.equals(memberAccessorId, k.memberAccessorId);
        }
        /** {@inheritDoc} */
        @Override public int hashCode() { return Objects.hash(groupId, memberAccessorId); }
    }
}
