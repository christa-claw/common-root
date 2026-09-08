package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.Acl;
import org.religioustext.app.model.user.BasicLevel;
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
 * Email-keyed facade for the MODE-1 named/shared ACL admin editor (docs/access-control.md §4a) —
 * the direct-editing counterpart to {@link CommentAclService}. The admin page knows only the
 * signed-in email and an ACL id; this loads entities, hands back plain DTOs materialised
 * in-transaction, and routes every mutation through {@link AccessService} so it is admin-gated and
 * owner-floor clamped. Editing a shared ACL affects EVERY object attached to it.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Service
public class NamedAclService {

    /** One named ACL in the admin list. {@code attachedOrg} is the org whose default points at it,
     *  or {@code null} for the system default / an unattached ACL. */
    public record AclSummary(String id, String name, String label, boolean system, String attachedOrg) { }

    /** One accessor entry of a named ACL, for display. */
    public record AceRow(String accessorKind, String accessorId, String accessorLabel,
                         String basicLevel, String extPerms) { }

    /** A named ACL's editable view: its label, whether it is a reserved/system ACL, and its rows. */
    public record NamedAclView(String id, String label, boolean system, List<AceRow> entries) { }

    /** A pickable accessor for the "add" control. */
    public record AccessorOption(String accessorKind, String accessorId, String label) { }

    /** An org member group the admin can attach a new ACL to. */
    public record OrgOption(String groupId, String name) { }

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final AccessService access;
    private final AclService acls;
    private final GroupService groups;

    public NamedAclService(final UserRepository aUserRepository, final AccessService anAccessService,
                           final AclService anAclService, final GroupService aGroupService) {
        this.users = aUserRepository;
        this.access = anAccessService;
        this.acls = anAclService;
        this.groups = aGroupService;
    }

    /**
     * Every named/shared ACL (system default + each org default + any hand-crafted), annotated
     * with the org it governs. Admin only.
     *
     * @param anEmail the signed-in user's email
     * @return the named ACLs as {@link AclSummary} rows, label-ordered
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional(readOnly = true)
    public List<AclSummary> list(final String anEmail) {
        requireAdmin(anEmail);
        final Map<String, String> orgByAclId = new HashMap<>();
        for (final AccessGroup g : em.createQuery(
                "SELECT g FROM AccessGroup g WHERE g.defaultAclId IS NOT NULL", AccessGroup.class)
                .getResultList()) {
            orgByAclId.put(g.getDefaultAclId(), groups.displayName(g));
        }
        final List<AclSummary> out = new ArrayList<>();
        for (final Acl acl : acls.listNamed()) {
            out.add(new AclSummary(acl.getId(), acl.getName(), acl.getLabel(),
                acl.isSystem(), orgByAclId.get(acl.getId())));
        }
        return out;
    }

    /**
     * One named ACL as an editable view. Admin only.
     *
     * @param anEmail the signed-in user's email
     * @param anAclId the ACL's id
     * @return the {@link NamedAclView}, or {@code null} if no ACL has that id
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional(readOnly = true)
    public NamedAclView view(final String anEmail, final String anAclId) {
        requireAdmin(anEmail);
        final Acl acl = acls.findById(anAclId);
        if (acl == null) return null;
        final List<AceRow> rows = new ArrayList<>();
        for (final Ace e : acl.getEntries()) {
            rows.add(new AceRow(e.getAccessorKind().name(), e.getAccessorId(),
                labelFor(e.getAccessorKind(), e.getAccessorId()),
                e.getBasicLevel().name(), e.getExtPerms()));
        }
        return new NamedAclView(acl.getId(), acl.getLabel(), acl.isSystem(), rows);
    }

    /**
     * Change (or add) a grant on a named ACL — admin-gated, owner-floor clamped.
     *
     * @param anEmail        the signed-in user's email
     * @param anAclId        the ACL's id
     * @param anAccessorKind an {@link Ace.AccessorKind} name
     * @param anAccessorId   the accessor id, or {@code null}/blank for a special
     * @param aBasicLevel    a {@link BasicLevel} name
     * @param anExtPerms     extended permissions, or {@code null}/blank
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional
    public void setGrant(final String anEmail, final String anAclId, final String anAccessorKind,
                         final String anAccessorId, final String aBasicLevel, final String anExtPerms) {
        access.setSharedGrantById(caller(anEmail), anAclId,
            Ace.AccessorKind.valueOf(anAccessorKind), blank(anAccessorId),
            BasicLevel.valueOf(aBasicLevel), blank(anExtPerms));
    }

    /**
     * Remove an accessor's grant from a named ACL — admin-gated.
     *
     * @param anEmail        the signed-in user's email
     * @param anAclId        the ACL's id
     * @param anAccessorKind an {@link Ace.AccessorKind} name
     * @param anAccessorId   the accessor id, or {@code null}/blank for a special
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional
    public void removeGrant(final String anEmail, final String anAclId,
                            final String anAccessorKind, final String anAccessorId) {
        access.removeSharedGrantById(caller(anEmail), anAclId,
            Ace.AccessorKind.valueOf(anAccessorKind), blank(anAccessorId));
    }

    /**
     * Rename a named ACL's human label — admin-gated.
     *
     * @param anEmail the signed-in user's email
     * @param anAclId the ACL's id
     * @param aLabel  the new label
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional
    public void rename(final String anEmail, final String anAclId, final String aLabel) {
        access.renameAcl(caller(anEmail), anAclId, aLabel);
    }

    /**
     * Accessors that can still be ADDED to this ACL — every accessor minus those already granted.
     *
     * @param anEmail the signed-in user's email
     * @param anAclId the ACL's id
     * @return the grantable {@link AccessorOption}s not already present
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional(readOnly = true)
    public List<AccessorOption> availableAccessors(final String anEmail, final String anAclId) {
        requireAdmin(anEmail);
        final Acl acl = acls.findById(anAclId);
        final Set<String> present = new HashSet<>();
        if (acl != null)
            for (final Ace e : acl.getEntries())
                present.add(key(e.getAccessorKind().name(), e.getAccessorId()));
        final List<AccessorOption> all = allAccessors();
        all.removeIf(o -> present.contains(key(o.accessorKind(), o.accessorId())));
        return all;
    }

    /**
     * Org member groups a new ACL can be attached to (for the "new ACL" picker). Admin only.
     *
     * @param anEmail the signed-in user's email
     * @return the orgs as {@link OrgOption}s, name-ordered
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional(readOnly = true)
    public List<OrgOption> orgs(final String anEmail) {
        requireAdmin(anEmail);
        final List<OrgOption> out = new ArrayList<>();
        em.createQuery("SELECT g FROM AccessGroup g ORDER BY g.name", AccessGroup.class)
            .getResultList().forEach(g -> out.add(new OrgOption(g.getId(), groups.displayName(g))));
        return out;
    }

    /**
     * Create a new named ACL and attach it as the given org's default — admin-gated.
     *
     * @param anEmail  the signed-in user's email
     * @param aGroupId the org member group to attach to
     * @param aLabel   the human label for the new ACL
     * @return the new ACL's id
     * @throws IllegalStateException if not signed in / unknown account / not an admin
     */
    @Transactional
    public String createForOrg(final String anEmail, final String aGroupId, final String aLabel) {
        return access.createOrgAcl(caller(anEmail), aGroupId, aLabel).getId();
    }

    // ---- helpers ----

    private static String key(final String aKind, final String anId) {
        return aKind + "|" + (anId == null ? "" : anId);
    }

    /** Every grantable accessor: the specials, every group, every real user. Roles are NOT
     *  offered — privileges are granted to accessors only; the role ladder is the separate
     *  system-wide privilege plane (docs/access-control.md §2). */
    private List<AccessorOption> allAccessors() {
        final List<AccessorOption> out = new ArrayList<>();
        out.add(new AccessorOption("world", null, "Everyone"));
        out.add(new AccessorOption("users", null, "Signed-in users"));
        out.add(new AccessorOption("owner", null, "Owner"));
        em.createQuery("SELECT g FROM AccessGroup g ORDER BY g.name", AccessGroup.class)
            .getResultList().forEach(g -> out.add(new AccessorOption("group", g.getId(), groups.displayName(g))));
        em.createQuery("SELECT u FROM User u WHERE u.system = false ORDER BY u.displayName", User.class)
            .getResultList().forEach(u -> out.add(new AccessorOption("user", u.getId(),
                (u.getDisplayName() != null ? u.getDisplayName() : u.getEmail()))));
        return out;
    }

    private String labelFor(final Ace.AccessorKind aKind, final String anAccessorId) {
        return switch (aKind) {
            case user -> {
                final User u = anAccessorId == null ? null : em.find(User.class, anAccessorId);
                yield u == null ? anAccessorId
                    : (u.getDisplayName() != null ? u.getDisplayName() : u.getEmail());
            }
            case group -> {
                final AccessGroup g = anAccessorId == null ? null : em.find(AccessGroup.class, anAccessorId);
                yield g == null ? anAccessorId : groups.displayName(g);
            }
            case role, world, users, owner -> null;   // the UI localises these by kind
        };
    }

    private User caller(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) throw new IllegalStateException("Not signed in");
        return users.findByEmailIgnoreCase(anEmail.trim())
            .orElseThrow(() -> new IllegalStateException("No account for: " + anEmail));
    }

    private void requireAdmin(final String anEmail) {
        if (!access.canManageAcls(caller(anEmail)))
            throw new IllegalStateException("Only an admin may view or edit shared ACLs");
    }

    private static String blank(final String aValue) { return aValue == null || aValue.isBlank() ? null : aValue; }
}
