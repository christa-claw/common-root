package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.Acl;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.BasicLevel;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Email-keyed facade for the comment-context ACL editor (docs/access-control.md §4a). The UI
 * knows only the signed-in email + a comment's public id; this loads the caller + comment
 * entities, hands back plain DTOs (materialised in-transaction), and routes edits through
 * {@link AccessService} so every mutation is permission-checked and copy-on-write.
 *
 * Comments are looked up by their STABLE public_id (the row id churns for seeded comments).
 * NOTE: a custom ACL attached to a SEEDED (channel) comment does not survive the startup
 * reseed — the row is recreated with acl_id NULL. Per-comment ACL edits are durable on
 * USER-authored comments; on channel comments, prefer editing the org (shared) ACL directly.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Service
public class CommentAclService {

    /** One accessor entry of the effective ACL, for display. */
    public record AceRow(String accessorKind, String accessorId, String accessorLabel,
                         String basicLevel, String extPerms) { }

    /** The effective ACL of a comment + whether the caller may change it. */
    public record AclView(String sourceLabel, boolean custom, boolean canChange, List<AceRow> entries) { }

    /** A pickable accessor for the "add" control (kind + id + human label). */
    public record AccessorOption(String accessorKind, String accessorId, String label) { }

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final AccessService access;
    private final AclService acls;
    private final GroupService groups;

    public CommentAclService(final UserRepository aUserRepository, final AccessService anAccessService,
                             final AclService anAclService, final GroupService aGroupService) {
        this.users = aUserRepository;
        this.access = anAccessService;
        this.acls = anAclService;
        this.groups = aGroupService;
    }

    /**
     * The effective ACL of a comment as a display DTO, plus whether the caller may change it.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable {@code public_id}
     * @return an {@link AclView} (source label, custom flag, {@code canChange}, and the ACE rows)
     * @throws IllegalStateException if not signed in, the account is unknown, or no comment has
     *                               that public id
     * @see AclService#effectiveAclFor(String, String)
     */
    @Transactional(readOnly = true)
    public AclView viewFor(final String anEmail, final String aPublicId) {
        final User caller = caller(anEmail);
        final Comment comment = byPublicId(aPublicId);
        final Acl acl = acls.effectiveAclFor(comment.getAclId(), orgDefaultAclId(comment));

        final List<AceRow> rows = new ArrayList<>();
        if (acl != null) {
            for (final Ace e : acl.getEntries()) {
                rows.add(new AceRow(
                    e.getAccessorKind().name(),
                    e.getAccessorId(),
                    labelFor(e.getAccessorKind(), e.getAccessorId()),
                    e.getBasicLevel().name(),
                    e.getExtPerms()));
            }
        }
        final boolean custom = acl != null && acl.isCustom();
        final String sourceLabel = acl == null ? null : acl.getLabel();
        return new AclView(sourceLabel, custom, access.canChangeAcl(caller, comment), rows);
    }

    /**
     * Change (or add) a grant on this comment — copy-on-write via {@link AccessService} (gated).
     *
     * @param anEmail        the signed-in user's email
     * @param aPublicId      the comment's stable {@code public_id}
     * @param anAccessorKind an {@link Ace.AccessorKind} name (e.g. {@code "world"}, {@code "group"})
     * @param anAccessorId   the accessor id, or {@code null}/blank for the specials
     * @param aBasicLevel    a {@link BasicLevel} name (e.g. {@code "read"}, {@code "write"})
     * @param anExtPerms     extended permissions, or {@code null}/blank
     * @throws IllegalStateException if not signed in / unknown account / comment not found, or
     *                               the caller may not change this comment's permissions
     * @throws IllegalArgumentException if {@code anAccessorKind} or {@code aBasicLevel} is not a
     *                                  valid enum name
     * @see AccessService#changeCommentGrant(User, Comment, Ace.AccessorKind, String, BasicLevel, String)
     */
    @Transactional
    public void setGrant(final String anEmail, final String aPublicId, final String anAccessorKind,
                         final String anAccessorId, final String aBasicLevel, final String anExtPerms) {
        access.changeCommentGrant(caller(anEmail), byPublicId(aPublicId),
            Ace.AccessorKind.valueOf(anAccessorKind), blank(anAccessorId),
            BasicLevel.valueOf(aBasicLevel), blank(anExtPerms));
    }

    /**
     * Remove an accessor's grant from this comment (copy-on-write via {@link AccessService}, gated).
     *
     * @param anEmail        the signed-in user's email
     * @param aPublicId      the comment's stable {@code public_id}
     * @param anAccessorKind an {@link Ace.AccessorKind} name
     * @param anAccessorId   the accessor id, or {@code null}/blank for a special
     * @throws IllegalStateException if not signed in / unknown account / comment not found, or the
     *                               caller may not change this comment's permissions
     * @see AccessService#removeCommentGrant(User, Comment, Ace.AccessorKind, String)
     */
    @Transactional
    public void removeGrant(final String anEmail, final String aPublicId,
                            final String anAccessorKind, final String anAccessorId) {
        access.removeCommentGrant(caller(anEmail), byPublicId(aPublicId),
            Ace.AccessorKind.valueOf(anAccessorKind), blank(anAccessorId));
    }

    /**
     * Accessors that can still be ADDED to this comment's ACL — every accessor MINUS those
     * already granted, so no principal can be granted twice (no competing rights).
     *
     * @param aPublicId the comment's stable {@code public_id}
     * @return the grantable {@link AccessorOption}s not already present on the effective ACL
     * @throws IllegalStateException if no comment has that public id
     */
    @Transactional(readOnly = true)
    public List<AccessorOption> availableAccessors(final String aPublicId) {
        final Comment comment = byPublicId(aPublicId);
        final Acl acl = acls.effectiveAclFor(comment.getAclId(), orgDefaultAclId(comment));
        final Set<String> present = new HashSet<>();
        if (acl != null)
            for (final Ace e : acl.getEntries())
                present.add(key(e.getAccessorKind().name(), e.getAccessorId()));
        final List<AccessorOption> all = allAccessors();
        all.removeIf(o -> present.contains(key(o.accessorKind(), o.accessorId())));
        return all;
    }

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

    /**
     * Reset the comment to inherit (drop its custom ACL) — gated, and reclaims the ACL row.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable {@code public_id}
     * @throws IllegalStateException if not signed in / unknown account / comment not found, or the
     *                               caller may not change this comment's permissions
     * @see AclService#detachCommentAcl(Comment)
     */
    @Transactional
    public void reset(final String anEmail, final String aPublicId) {
        final User caller = caller(anEmail);
        final Comment comment = byPublicId(aPublicId);
        if (!access.canChangeAcl(caller, comment))
            throw new IllegalStateException("Not permitted to change this comment's permissions");
        acls.detachCommentAcl(comment);
    }

    // ---- helpers ----

    private String orgDefaultAclId(final Comment aComment) {
        final User owner = aComment.getUser();
        if (owner != null && owner.isSystem()) {
            final AccessGroup g = groups.findChannelMemberGroup(owner.getId());
            if (g != null) return g.getDefaultAclId();
        }
        return null;
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
            case role, world, users, owner -> null;   // UI localises these by kind
        };
    }

    private User caller(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) throw new IllegalStateException("Not signed in");
        return users.findByEmailIgnoreCase(anEmail.trim())
            .orElseThrow(() -> new IllegalStateException("No account for: " + anEmail));
    }

    private Comment byPublicId(final String aPublicId) {
        return em.createQuery("SELECT c FROM Comment c WHERE c.publicId = :p", Comment.class)
            .setParameter("p", aPublicId).setMaxResults(1).getResultList().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Comment not found: " + aPublicId));
    }

    private static String blank(final String aValue) { return aValue == null || aValue.isBlank() ? null : aValue; }
}
