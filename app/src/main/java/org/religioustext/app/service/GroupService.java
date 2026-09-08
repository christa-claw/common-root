package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.GroupMembership;
import org.religioustext.app.model.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ACL-plane groups: per-channel member groups and their membership (docs/access-control.md §4). Members
 * are ACCESSORS — users OR groups (nesting) — so a user in a child group is TRANSITIVELY a
 * member of the parent. EntityManager-backed; decisions live in {@link AccessService}.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Service
public class GroupService {

    @PersistenceContext
    private EntityManager em;

    /** Conventional name of a channel account's member group — the accessors the org grants extra
     *  privileges over its content. They do NOT own it (the channel account is the owner, floored
     *  at read); their {@code write} grant is what lets them edit the org's comments. */
    public static String channelMemberGroupName(final String aChannelUserId) {
        return "channel-members:" + aChannelUserId;
    }

    /** Human display name for a group. For a channel member group ({@code channel-members:&lt;id&gt;})
     *  this is the channel account's display name + " members" (e.g. "Ali Dawah members"); for any
     *  other group it's the raw {@code name}. Falls back to the raw name if the channel can't be
     *  resolved. */
    @Transactional(readOnly = true)
    public String displayName(final AccessGroup aGroup) {
        if (aGroup == null) return null;
        final String name = aGroup.getName();
        final String prefix = "channel-members:";
        if (name != null && name.startsWith(prefix)) {
            final User channel = em.find(User.class, name.substring(prefix.length()));
            if (channel != null && channel.getDisplayName() != null && !channel.getDisplayName().isBlank())
                return channel.getDisplayName() + " members";
        }
        final String label = aGroup.getLabel();
        return label != null && !label.isBlank() ? label : name;
    }

    /** The member group for a channel account, or null if not provisioned yet (read-only). */
    @Transactional(readOnly = true)
    public AccessGroup findChannelMemberGroup(final String aChannelUserId) {
        final List<AccessGroup> found = em.createQuery(
                "SELECT g FROM AccessGroup g WHERE g.name = :n", AccessGroup.class)
            .setParameter("n", channelMemberGroupName(aChannelUserId))
            .setMaxResults(1)
            .getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    /** Find (or lazily create) the member group for a channel account. */
    @Transactional
    public AccessGroup findOrCreateChannelMemberGroup(final String aChannelUserId) {
        final AccessGroup existing = findChannelMemberGroup(aChannelUserId);
        if (existing != null) return existing;
        final AccessGroup group = new AccessGroup();
        group.setName(channelMemberGroupName(aChannelUserId));
        group.setSystem(true);
        em.persist(group);
        return group;
    }

    // ---- admin-created groups (accessor management) -------------------------

    /**
     * Creates a NON-system group (admin accessors view). The {@code channel-members:} namespace
     * is reserved for the seeded per-channel groups; names must be unique.
     *
     * @param aName        the unique internal name
     * @param aLabel       optional display title (blank → none)
     * @param aDescription optional description of what the group is for (blank → none)
     * @return the persisted group
     * @throws IllegalArgumentException on a blank/reserved/taken name
     */
    @Transactional
    public AccessGroup createGroup(final String aName
                                   , final String aLabel
                                   , final String aDescription) {
        if (aName == null || aName.isBlank())
            throw new IllegalArgumentException("Group name is required");
        final String name = aName.trim();
        if (name.startsWith("channel-members:"))
            throw new IllegalArgumentException("The channel-members: prefix is reserved for seeded channel groups");
        if (!em.createQuery("SELECT 1 FROM AccessGroup g WHERE g.name = :n")
                .setParameter("n", name).setMaxResults(1).getResultList().isEmpty())
            throw new IllegalArgumentException("A group named \"" + name + "\" already exists");

        final AccessGroup group = new AccessGroup();
        group.setName(name);
        group.setLabel(aLabel == null || aLabel.isBlank() ? null : aLabel.trim());
        group.setDescription(aDescription == null || aDescription.isBlank() ? null : aDescription.trim());
        group.setSystem(false);
        em.persist(group);
        return group;
    }

    /**
     * Deletes a NON-system group along with its membership rows — both the members it contains
     * and its own memberships in other groups (there is no member FK; see
     * {@link #removeAccessorEverywhere}). ACEs naming the group are left to the ACL plane.
     *
     * @param aGroupId the group's {@code grp-} id
     * @throws IllegalArgumentException if the group is unknown
     * @throws IllegalStateException    if the group is a system group
     */
    @Transactional
    public void deleteGroup(final String aGroupId) {
        final AccessGroup group = em.find(AccessGroup.class, aGroupId);
        if (group == null) throw new IllegalArgumentException("No such group: " + aGroupId);
        if (group.isSystem()) throw new IllegalStateException("System groups cannot be deleted");
        em.createQuery("DELETE FROM GroupMembership m WHERE m.groupId = :g")
            .setParameter("g", aGroupId).executeUpdate();
        removeAccessorEverywhere(aGroupId);
        em.remove(group);
    }

    /** Every group, name-ordered — the admin accessors grid. */
    @Transactional(readOnly = true)
    public List<AccessGroup> listGroups() {
        return em.createQuery("SELECT g FROM AccessGroup g ORDER BY g.name", AccessGroup.class)
            .getResultList();
    }

    /** A group's direct membership rows (users and nested groups). */
    @Transactional(readOnly = true)
    public List<GroupMembership> membersOf(final String aGroupId) {
        return em.createQuery(
                "SELECT m FROM GroupMembership m WHERE m.groupId = :g", GroupMembership.class)
            .setParameter("g", aGroupId).getResultList();
    }

    // ---- membership (accessor: user or group) -------------------------------

    /** Add an accessor (user or group) as a member of a group. */
    @Transactional
    public void addMember(final String aGroupId, final String anAccessorId, final Ace.AccessorKind aKind) {
        if (isMember(aGroupId, anAccessorId)) return;
        em.persist(new GroupMembership(aGroupId, anAccessorId, aKind));
    }

    /** Add a user member. */
    @Transactional
    public void addMember(final String aGroupId, final String aUserId) {
        addMember(aGroupId, aUserId, Ace.AccessorKind.user);
    }

    /** Nest a group inside another group. */
    @Transactional
    public void addMemberGroup(final String aParentGroupId, final String aChildGroupId) {
        addMember(aParentGroupId, aChildGroupId, Ace.AccessorKind.group);
    }

    @Transactional
    public void removeMember(final String aGroupId, final String anAccessorId) {
        em.createQuery(
                "DELETE FROM GroupMembership m WHERE m.groupId = :g AND m.memberAccessorId = :a")
            .setParameter("g", aGroupId).setParameter("a", anAccessorId)
            .executeUpdate();
    }

    /** App-level cleanup (there is no member FK): drop an accessor's memberships everywhere.
     *  Call when a user or group is deleted. */
    @Transactional
    public void removeAccessorEverywhere(final String anAccessorId) {
        em.createQuery("DELETE FROM GroupMembership m WHERE m.memberAccessorId = :a")
            .setParameter("a", anAccessorId).executeUpdate();
    }

    @Transactional(readOnly = true)
    public boolean isMember(final String aGroupId, final String anAccessorId) {
        if (aGroupId == null || anAccessorId == null) return false;
        return !em.createQuery(
                "SELECT 1 FROM GroupMembership m WHERE m.groupId = :g AND m.memberAccessorId = :a")
            .setParameter("g", aGroupId).setParameter("a", anAccessorId)
            .setMaxResults(1).getResultList().isEmpty();
    }

    // ---- transitive resolution (nesting) ------------------------------------

    /**
     * ALL group ids an accessor belongs to, TRANSITIVELY (direct memberships + every parent
     * group reached through nested-group memberships) — for building ACL accessor keys.
     * Recursive CTE; {@code UNION} dedups, so cycles terminate safely.
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<String> groupIdsForUser(final String anAccessorId) {
        if (anAccessorId == null) return List.of();
        return em.createNativeQuery(
                "WITH RECURSIVE grp(id) AS ("
              + "  SELECT group_id FROM group_memberships WHERE member_accessor_id = ?1"
              + "  UNION "
              + "  SELECT gm.group_id FROM group_memberships gm "
              + "    JOIN grp ON gm.member_accessor_id = grp.id AND gm.member_kind = 'group'"
              + ") SELECT id FROM grp")
            .setParameter(1, anAccessorId)
            .getResultList();
    }

    /** True if the user is (transitively) in the named group. */
    @Transactional(readOnly = true)
    public boolean isMemberOfNamed(final String aGroupName, final String anAccessorId) {
        final AccessGroup group = em.createQuery(
                "SELECT g FROM AccessGroup g WHERE g.name = :n", AccessGroup.class)
            .setParameter("n", aGroupName).setMaxResults(1).getResultList()
            .stream().findFirst().orElse(null);
        return group != null && groupIdsForUser(anAccessorId).contains(group.getId());
    }

    /** True if the user is (transitively) a member of the channel's group and may edit its comments. */
    @Transactional(readOnly = true)
    public boolean isChannelMember(final String aChannelUserId, final String aUserId) {
        return isMemberOfNamed(channelMemberGroupName(aChannelUserId), aUserId);
    }
}
