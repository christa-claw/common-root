package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.GroupMembership;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Email-keyed facade for the admin ACCESSOR editor ({@code AdminAccessorsView}) — creating and
 * managing the principals themselves (users and groups), the counterpart to
 * {@link NamedAclService}, which manages what those principals are granted. The view knows only
 * the signed-in email and accessor ids; this loads entities, hands back plain DTOs materialised
 * in-transaction, and enforces the role ladder on every mutation: a rung may only be handed out
 * by the rung that governs it (contributor by admin, admin by superuser — see {@link Role}).
 *
 * @author Christa Claw
 * @version 0.6.3-SNAPSHOT
 * @since 0.6.3
 */
@Service
public class AccessorAdminService {

    /** One account in the admin users list. */
    public record UserRow(String id, String email, String displayName, Role role,
                          boolean verified, boolean active, boolean system) { }

    /** One group in the admin groups list. */
    public record GroupRow(String id, String name, String label, String description,
                           boolean system, long memberCount) { }

    /** One direct member of a group, for display. */
    public record MemberRow(String accessorId, Ace.AccessorKind kind, String label) { }

    /** A pickable accessor for the add-member control. */
    public record MemberOption(String accessorId, Ace.AccessorKind kind, String label) { }

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final UserService    userService;
    private final GroupService   groupService;

    public AccessorAdminService(final UserRepository aUserRepository
                                , final UserService aUserService
                                , final GroupService aGroupService) {
        this.users        = aUserRepository;
        this.userService  = aUserService;
        this.groupService = aGroupService;
    }

    // ---- listings -----------------------------------------------------------

    /**
     * Every account, email-ordered. Admin only.
     *
     * @param anEmail the signed-in user's email
     * @return all users as {@link UserRow}s, system (channel) accounts included
     */
    @Transactional(readOnly = true)
    public List<UserRow> users(final String anEmail) {
        requireAdmin(anEmail);
        return userService.listAccounts().stream()
            .map(u -> new UserRow(u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                                  u.isVerified(), u.isActive(), u.isSystem()))
            .toList();
    }

    /**
     * Every group, name-ordered, with its direct member count. Admin only.
     *
     * @param anEmail the signed-in user's email
     * @return all groups as {@link GroupRow}s
     */
    @Transactional(readOnly = true)
    public List<GroupRow> groups(final String anEmail) {
        requireAdmin(anEmail);
        final Map<String, Long> counts = new HashMap<>();
        for (final Object[] row : em.createQuery(
                "SELECT m.groupId, COUNT(m) FROM GroupMembership m GROUP BY m.groupId", Object[].class)
                .getResultList()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return groupService.listGroups().stream()
            .map(g -> new GroupRow(g.getId(), g.getName(), groupService.displayName(g),
                                   g.getDescription(), g.isSystem(),
                                   counts.getOrDefault(g.getId(), 0L)))
            .toList();
    }

    /**
     * A group's direct members with display labels. Admin only.
     *
     * @param anEmail  the signed-in user's email
     * @param aGroupId the group's {@code grp-} id
     * @return the direct members as {@link MemberRow}s, label-ordered
     */
    @Transactional(readOnly = true)
    public List<MemberRow> members(final String anEmail, final String aGroupId) {
        requireAdmin(anEmail);
        final List<MemberRow> rows = new ArrayList<>();
        for (final GroupMembership m : groupService.membersOf(aGroupId)) {
            rows.add(new MemberRow(m.getMemberAccessorId(), m.getMemberKind(),
                                   accessorLabel(m.getMemberAccessorId(), m.getMemberKind())));
        }
        rows.sort((a, b) -> a.label().compareToIgnoreCase(b.label()));
        return rows;
    }

    /**
     * The accessors that could still be added to a group: every user and group except the group
     * itself and its existing direct members. Admin only.
     *
     * @param anEmail  the signed-in user's email
     * @param aGroupId the group's {@code grp-} id
     * @return the pickable accessors, users first
     */
    @Transactional(readOnly = true)
    public List<MemberOption> memberOptions(final String anEmail, final String aGroupId) {
        requireAdmin(anEmail);
        final Set<String> taken = new HashSet<>();
        taken.add(aGroupId);
        for (final GroupMembership m : groupService.membersOf(aGroupId))
            taken.add(m.getMemberAccessorId());

        final List<MemberOption> options = new ArrayList<>();
        for (final User u : userService.listAccounts()) {
            if (taken.contains(u.getId())) continue;
            options.add(new MemberOption(u.getId(), Ace.AccessorKind.user,
                accessorLabel(u.getId(), Ace.AccessorKind.user) + (u.isSystem() ? " (channel)" : "")));
        }
        for (final AccessGroup g : groupService.listGroups()) {
            if (taken.contains(g.getId())) continue;
            options.add(new MemberOption(g.getId(), Ace.AccessorKind.group,
                groupService.displayName(g) + " (group)"));
        }
        return options;
    }

    /**
     * The rungs this actor may hand out: every role whose governing rung the actor holds
     * (admin → consumer+contributor; superuser → all four).
     *
     * @param anEmail the signed-in user's email
     * @return the grantable roles, ladder-ordered
     */
    @Transactional(readOnly = true)
    public List<Role> grantableRoles(final String anEmail) {
        final User actor = requireAdmin(anEmail);
        return List.of(Role.values()).stream()
            .filter(r -> actor.getRole().atLeast(granterFor(r)))
            .toList();
    }

    // ---- mutations ----------------------------------------------------------

    /**
     * Invites a new account (see {@link UserService#invite}). The actor must hold the rung that
     * governs the starting role.
     *
     * @param anEmail        the signed-in user's email
     * @param anInviteeEmail the invitee's address
     * @param aDisplayName   optional display name
     * @param aRole          the invitee's starting rung ({@code null} → consumer)
     * @return the new account's {@code usr-} id
     */
    @Transactional
    public String invite(final String anEmail
                         , final String anInviteeEmail
                         , final String aDisplayName
                         , final Role aRole) {
        final User actor = requireAdmin(anEmail);
        final Role role = aRole == null ? Role.consumer : aRole;
        requireGrantable(actor, role);
        return userService.invite(anInviteeEmail, aDisplayName, role).getId();
    }

    /**
     * Re-issues the claim link for a not-yet-verified invited account.
     *
     * @param anEmail the signed-in user's email
     * @param aUserId the invited account's {@code usr-} id
     */
    @Transactional
    public void resendInvite(final String anEmail, final String aUserId) {
        requireAdmin(anEmail);
        userService.resendInvite(aUserId);
    }

    /**
     * Moves an account to another rung. The actor must hold the governing rung for BOTH the
     * current and the new role (an admin can neither promote to admin nor demote one), and may
     * not change their own rung (no accidental self-lockout).
     *
     * @param anEmail the signed-in user's email
     * @param aUserId the account's {@code usr-} id
     * @param aRole   the new rung
     */
    @Transactional
    public void setRole(final String anEmail, final String aUserId, final Role aRole) {
        final User actor = requireAdmin(anEmail);
        if (actor.getId().equals(aUserId))
            throw new IllegalStateException("You cannot change your own role");
        final User target = users.findById(aUserId)
            .orElseThrow(() -> new IllegalArgumentException("No such user: " + aUserId));
        requireGrantable(actor, target.getRole());
        requireGrantable(actor, aRole);
        userService.setRole(aUserId, aRole);
    }

    /**
     * Creates a non-system group (see {@link GroupService#createGroup}).
     *
     * @param anEmail      the signed-in user's email
     * @param aName        the unique internal name
     * @param aLabel       optional display title
     * @param aDescription optional description
     * @return the new group's {@code grp-} id
     */
    @Transactional
    public String createGroup(final String anEmail
                              , final String aName
                              , final String aLabel
                              , final String aDescription) {
        requireAdmin(anEmail);
        return groupService.createGroup(aName, aLabel, aDescription).getId();
    }

    /**
     * Deletes a non-system group (see {@link GroupService#deleteGroup}).
     *
     * @param anEmail  the signed-in user's email
     * @param aGroupId the group's {@code grp-} id
     */
    @Transactional
    public void deleteGroup(final String anEmail, final String aGroupId) {
        requireAdmin(anEmail);
        groupService.deleteGroup(aGroupId);
    }

    /**
     * Adds an accessor to a group. Direct self-membership is refused; deeper cycles are tolerated
     * (the transitive resolution dedups, so they terminate) but pointless.
     *
     * @param anEmail  the signed-in user's email
     * @param aGroupId the group's {@code grp-} id
     * @param anOption the accessor to add
     */
    @Transactional
    public void addMember(final String anEmail, final String aGroupId, final MemberOption anOption) {
        requireAdmin(anEmail);
        if (aGroupId.equals(anOption.accessorId()))
            throw new IllegalArgumentException("A group cannot be a member of itself");
        groupService.addMember(aGroupId, anOption.accessorId(), anOption.kind());
    }

    /**
     * Removes an accessor from a group.
     *
     * @param anEmail     the signed-in user's email
     * @param aGroupId    the group's {@code grp-} id
     * @param anAccessorId the member to remove
     */
    @Transactional
    public void removeMember(final String anEmail, final String aGroupId, final String anAccessorId) {
        requireAdmin(anEmail);
        groupService.removeMember(aGroupId, anAccessorId);
    }

    // ---- helpers ------------------------------------------------------------

    /** The rung that governs handing out {@code aRole} (see {@link Role}): contributor is
     *  admin-granted, admin (and superuser) are superuser-granted, consumer is the admin-granted
     *  floor (it is what a demotion FROM contributor lands on). */
    private static Role granterFor(final Role aRole) {
        return switch (aRole) {
            case consumer, contributor -> Role.admin;
            case admin, superuser      -> Role.superuser;
        };
    }

    private static void requireGrantable(final User anActor, final Role aRole) {
        if (!anActor.getRole().atLeast(granterFor(aRole)))
            throw new IllegalStateException("Only a " + granterFor(aRole) + " may grant or revoke " + aRole);
    }

    private String accessorLabel(final String anAccessorId, final Ace.AccessorKind aKind) {
        if (aKind == Ace.AccessorKind.group) {
            final AccessGroup g = em.find(AccessGroup.class, anAccessorId);
            return g == null ? anAccessorId : groupService.displayName(g);
        }
        final User u = em.find(User.class, anAccessorId);
        if (u == null) return anAccessorId;
        return u.getDisplayName() != null && !u.getDisplayName().isBlank()
            ? u.getDisplayName() : u.getEmail();
    }

    private User requireAdmin(final String anEmail) {
        if (anEmail == null || anEmail.isBlank())
            throw new IllegalStateException("Not signed in");
        final User caller = users.findByEmailIgnoreCase(anEmail.trim())
            .orElseThrow(() -> new IllegalStateException("No account for: " + anEmail));
        if (!caller.isActive() || !caller.getRole().atLeast(Role.admin))
            throw new IllegalStateException("Only an admin may manage users and groups");
        return caller;
    }
}
