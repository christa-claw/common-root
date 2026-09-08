package org.religioustext.app.service;

import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.Acl;
import org.religioustext.app.model.user.BasicLevel;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.model.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * The access decision point (docs/access-control.md §2). Combines the TWO planes:
 *
 * <ol>
 *   <li><b>Role plane</b> — the system-wide {@link Role} ladder the caller carries.</li>
 *   <li><b>ACL plane</b> — per-object grants: the {@code owner} floor (read), and the
 *       per-channel member group (write), resolved via {@link GroupService}. Generic ACLs
 *       are evaluated by {@link AclService} once objects carry an acl id.</li>
 * </ol>
 *
 * The rule:
 * {@snippet lang="text" :
 *   effective = MAX(role-plane capability, ACL grants matched), then restrictions
 * }
 * The owner floor is {@link BasicLevel#OWNER_FLOOR} ({@link BasicLevel#read}) — an owner always
 * holds at least {@code read} and defaults to {@code delete}, but can be reduced to {@code read}
 * (never below) for retention. A {@link Role#superuser} is independent of that floor: it always
 * resolves to {@link BasicLevel#delete} (sees and does everything, which is always ≥ the owner).
 * Evaluated against {@link AclService} using the accessor keys from {@link #accessorKeys(User, boolean)}.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Service
public class AccessService {

    private final GroupService groups;
    private final AclService acls;

    public AccessService(final GroupService aGroupService, final AclService anAclService) {
        this.groups = aGroupService;
        this.acls = anAclService;
    }

    // ---- Comments -----------------------------------------------------------

    /** Effective basic level of {@code aCaller} on {@code aComment} (both planes, maxed). */
    @Transactional(readOnly = true)
    public BasicLevel effectiveLevel(final User aCaller, final Comment aComment) {
        if (aCaller == null || aComment == null) return BasicLevel.none;
        if (!aCaller.isActive()) return BasicLevel.none;            // global restriction

        // Superuser sees and does everything: full control (delete) on every object, which is
        // always ≥ whatever the owner holds. NOT tied to the owner floor (that is now 'read',
        // and lowering an owner for retention must never weaken Super).
        if (aCaller.getRole() == Role.superuser) return BasicLevel.delete;

        final User owner = aComment.getUser();
        final boolean isOwner = owner != null && owner.getId() != null
                && owner.getId().equals(aCaller.getId());

        // ---- Role plane (system-wide capability the caller carries) ----
        // Baseline NONE — world's grant comes from the ACL (org default = none, system
        // default = read), so the two defaults actually differ. Reading PUBLISHED comments is
        // the reader's own public path, separate from these action permissions.
        BasicLevel level = BasicLevel.none;
        if (aCaller.getRole().atLeast(Role.admin)) level = BasicLevel.max(level, BasicLevel.write);
        if (isOwner) level = BasicLevel.max(level, BasicLevel.OWNER_FLOOR);  // owner floor = read
        // Note: the owner's DEFAULT grant is still 'delete' (from the org/system default ACE,
        // applied via the ACL plane below); the floor only guarantees they never drop below read.

        // ---- ACL plane (per-object) via the full ACL chain ----
        // The comment's own CUSTOM ACL if it has one, else its ORG default (the owning
        // channel account's member group), else the SYSTEM default. Every comment therefore
        // gets an ACL-plane evaluation, even before anything is provisioned.
        level = BasicLevel.max(level,
            acls.resolveLevel(aComment.getAclId(), orgDefaultAclId(aComment), accessorKeys(aCaller, isOwner)));
        return level;
    }

    /** The org default ACL id governing a comment (its owning channel account's member group),
     *  or null for a plain user-authored comment. */
    private String orgDefaultAclId(final Comment aComment) {
        final User owner = aComment.getUser();
        if (owner != null && owner.isSystem()) {
            final var org = groups.findChannelMemberGroup(owner.getId());
            if (org != null) return org.getDefaultAclId();
        }
        return null;
    }

    /** The accessor keys a caller matches, for ACL evaluation (see {@link AclService}). Roles are
     *  deliberately NOT emitted: privileges are granted to accessors only (users, groups, the
     *  specials) — the role ladder is the separate system-wide privilege plane, already applied
     *  directly in {@link #effectiveLevel(User, Comment)}. A legacy {@code role} ACE therefore
     *  never matches. */
    private Set<String> accessorKeys(final User aCaller, final boolean theOwnerFlag) {
        final Set<String> keys = new HashSet<>();
        keys.add("world");                       // everyone (incl. anonymous)
        keys.add("users");                       // any signed-in principal (the caller is one)
        if (theOwnerFlag) keys.add("owner");
        for (final String gid : groups.groupIdsForUser(aCaller.getId())) keys.add("group:" + gid);
        return keys;
    }

    /** May the caller edit this comment's content? (write) */
    public boolean canEdit(final User aCaller, final Comment aComment) {
        return effectiveLevel(aCaller, aComment).atLeast(BasicLevel.write);
    }

    /**
     * BULK {@link #effectiveLevel(User, Comment)} for one caller over many comments — for
     * rendering a comment list with per-card affordances without one group/ACL query cascade
     * per card. The caller's accessor keys are computed ONCE (the recursive group closure is
     * the expensive part), and the org-default ACL of each distinct owner plus every ACL
     * object are cached across the batch — comments cluster under a handful of channels, so
     * the query count tracks the number of channels, not comments.
     *
     * @param aCaller     the caller (may be {@code null}/inactive — everything resolves none)
     * @param theComments the comments to evaluate
     * @return effective {@link BasicLevel} keyed by each comment's {@code publicId}
     *         (comments without a public id are skipped)
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, BasicLevel> effectiveLevels(final User aCaller,
                                                             final java.util.List<Comment> theComments) {
        final java.util.Map<String, BasicLevel> out = new java.util.HashMap<>();
        if (theComments == null || theComments.isEmpty()) return out;
        final boolean inactive = aCaller == null || !aCaller.isActive();
        final boolean superuser = !inactive && aCaller.getRole() == Role.superuser;

        // One key set without the owner special; per-comment we add "owner" only when it applies.
        final Set<String> baseKeys = inactive ? Set.of() : accessorKeys(aCaller, false);
        final Set<String> ownerKeys;
        if (inactive) {
            ownerKeys = Set.of();
        } else {
            ownerKeys = new HashSet<>(baseKeys);
            ownerKeys.add("owner");
        }

        // Caches: owner id -> org default ACL id (nullable), and ACL id -> ACL object.
        final java.util.Map<String, java.util.Optional<String>> orgAclIdByOwner = new java.util.HashMap<>();
        final java.util.Map<String, Acl> aclById = new java.util.HashMap<>();
        final Acl systemDefault = acls.findSystemDefault();

        for (final Comment c : theComments) {
            if (c.getPublicId() == null || c.getPublicId().isBlank()) continue;
            if (inactive) { out.put(c.getPublicId(), BasicLevel.none); continue; }
            if (superuser) { out.put(c.getPublicId(), BasicLevel.delete); continue; }

            final User owner = c.getUser();
            final boolean isOwner = owner != null && owner.getId() != null
                    && owner.getId().equals(aCaller.getId());

            BasicLevel level = BasicLevel.none;
            if (aCaller.getRole().atLeast(Role.admin)) level = BasicLevel.max(level, BasicLevel.write);
            if (isOwner) level = BasicLevel.max(level, BasicLevel.OWNER_FLOOR);

            // ACL chain with batch caches: custom -> owner's org default -> system default.
            Acl acl = null;
            if (c.getAclId() != null)
                acl = aclById.computeIfAbsent(c.getAclId(), acls::findById);
            if (acl == null && owner != null && owner.isSystem()) {
                final java.util.Optional<String> orgAclId = orgAclIdByOwner.computeIfAbsent(
                    owner.getId(), id -> {
                        final AccessGroup g = groups.findChannelMemberGroup(id);
                        return java.util.Optional.ofNullable(g == null ? null : g.getDefaultAclId());
                    });
                if (orgAclId.isPresent())
                    acl = aclById.computeIfAbsent(orgAclId.get(), acls::findById);
            }
            if (acl == null) acl = systemDefault;

            level = BasicLevel.max(level, acls.effectiveLevel(acl, isOwner ? ownerKeys : baseKeys));
            out.put(c.getPublicId(), level);
        }
        return out;
    }

    /** May the caller hard-delete this comment? (delete — owner or superuser only) */
    public boolean canDelete(final User aCaller, final Comment aComment) {
        return effectiveLevel(aCaller, aComment).atLeast(BasicLevel.delete);
    }

    /** May the caller publish/unpublish OTHERS' comments? (the 'moderate' extended perm) */
    public boolean canModerate(final User aCaller) {
        return aCaller != null && aCaller.isActive() && aCaller.getRole().atLeast(Role.admin);
    }

    /**
     * May the caller GRANT MORE privileges on this comment — i.e. change its ACL, e.g. raise
     * the org group from read → write (the Documentum 'change-permission' right)? Held by an
     * Admin (globally) OR the owner (on their own object). Superuser inherits ≥ owner.
     */
    public boolean canChangeAcl(final User aCaller, final Comment aComment) {
        if (aCaller == null || aComment == null || !aCaller.isActive()) return false;
        if (aCaller.getRole().atLeast(Role.admin)) return true;     // admin or superuser
        final User owner = aComment.getUser();
        return owner != null && owner.getId() != null && owner.getId().equals(aCaller.getId());
    }

    /** May the caller publish comments at all? (active + role ≥ contributor) — the author
     *  path, where ownership is implied by the create/own flow. */
    public boolean canPublish(final User aCaller) {
        return aCaller != null && aCaller.isActive() && aCaller.getRole().atLeast(Role.contributor);
    }

    /** May the caller make their OWN comment public? (role ≥ contributor + ownership) */
    public boolean canPublishOwn(final User aCaller, final Comment aComment) {
        if (aCaller == null || aComment == null || !aCaller.isActive()) return false;
        final User owner = aComment.getUser();
        final boolean isOwner = owner != null && owner.getId() != null
                && owner.getId().equals(aCaller.getId());
        return isOwner && aCaller.getRole().atLeast(Role.contributor);
    }

    // ---- ACL modification (the two modes, docs/access-control.md §4a) -------

    /**
     * MODE 2 — change a grant IN THE CONTEXT OF ONE COMMENT (copy-on-write). Affects only this
     * comment: breaks off a custom ACL on first edit, or modifies the existing custom one.
     *
     * @param aCaller      the acting user
     * @param aComment     the comment whose permissions are edited
     * @param aKind        the accessor kind ({@link Ace.AccessorKind})
     * @param anAccessorId the accessor id, or {@code null} for the specials
     *                     ({@link Ace.AccessorKind#world}/{@link Ace.AccessorKind#owner}/…)
     * @param aLevel       the {@link BasicLevel} to grant
     * @param anExtPerms   extended permissions (comma-set), or {@code null}
     * @return the upserted {@link Ace}
     * @throws IllegalStateException if {@code aCaller} may not change this comment's permissions
     * @see #canChangeAcl(User, Comment)
     * @see AclService#grantForComment(Comment, String, Ace.AccessorKind, String, BasicLevel, String)
     */
    @Transactional
    public Ace changeCommentGrant(final User aCaller, final Comment aComment,
                                  final Ace.AccessorKind aKind, final String anAccessorId,
                                  final BasicLevel aLevel, final String anExtPerms) {
        if (!canChangeAcl(aCaller, aComment))
            throw new IllegalStateException("Not permitted to change this comment's permissions");
        return acls.grantForComment(aComment, orgDefaultAclId(aComment), aKind, anAccessorId,
            flooredForOwner(aKind, aLevel), anExtPerms);
    }

    /**
     * Remove an accessor's grant on one comment (copy-on-write).
     *
     * @param aCaller      the acting user
     * @param aComment     the comment whose permissions are edited
     * @param aKind        the accessor kind to remove
     * @param anAccessorId the accessor id, or {@code null} for a special
     * @throws IllegalStateException if {@code aCaller} may not change this comment's permissions
     * @see #canChangeAcl(User, Comment)
     */
    @Transactional
    public void removeCommentGrant(final User aCaller, final Comment aComment,
                                   final Ace.AccessorKind aKind, final String anAccessorId) {
        if (!canChangeAcl(aCaller, aComment))
            throw new IllegalStateException("Not permitted to change this comment's permissions");
        acls.removeGrantForComment(aComment, orgDefaultAclId(aComment), aKind, anAccessorId);
    }

    /**
     * MODE 1 — change a grant DIRECTLY on a shared ACL (by name). Affects EVERY object attached
     * to it, so it is restricted to admins (a global act). Org-owner delegation on their own
     * org ACL is a later refinement.
     *
     * @param aCaller      the acting user; must be at least {@link Role#admin}
     * @param anAclName    the machine name of the shared ACL (e.g. {@code "channel-comments:<id>"})
     * @param aKind        the accessor kind
     * @param anAccessorId the accessor id, or {@code null} for a special
     * @param aLevel       the {@link BasicLevel} to grant
     * @param anExtPerms   extended permissions, or {@code null}
     * @return the upserted {@link Ace}, or {@code null} if no ACL has that name
     * @throws IllegalStateException if {@code aCaller} is not an active admin
     * @see AclService#setGrant(String, Ace.AccessorKind, String, BasicLevel, String)
     */
    @Transactional
    public Ace changeSharedAclGrant(final User aCaller, final String anAclName,
                                    final Ace.AccessorKind aKind, final String anAccessorId,
                                    final BasicLevel aLevel, final String anExtPerms) {
        if (aCaller == null || !aCaller.isActive() || !aCaller.getRole().atLeast(Role.admin))
            throw new IllegalStateException("Only an admin may modify a shared ACL directly");
        return acls.setGrant(anAclName, aKind, anAccessorId, flooredForOwner(aKind, aLevel), anExtPerms);
    }

    /** Whether the caller may manage (edit/create) named/shared ACLs — admins and above. */
    public boolean canManageAcls(final User aCaller) {
        return aCaller != null && aCaller.isActive() && aCaller.getRole().atLeast(Role.admin);
    }

    /**
     * MODE 1 by id — upsert a grant on a named/shared ACL (admin only, owner-floor clamped).
     *
     * @param aCaller      the acting user; must satisfy {@link #canManageAcls(User)}
     * @param anAclId      the named ACL's id
     * @param aKind        the accessor kind
     * @param anAccessorId the accessor id, or {@code null} for a special
     * @param aLevel       the {@link BasicLevel} to grant
     * @param anExtPerms   extended permissions, or {@code null}
     * @return the upserted {@link Ace}, or {@code null} if no ACL has that id
     * @throws IllegalStateException if the caller may not manage ACLs
     */
    @Transactional
    public Ace setSharedGrantById(final User aCaller, final String anAclId, final Ace.AccessorKind aKind,
                                  final String anAccessorId, final BasicLevel aLevel, final String anExtPerms) {
        if (!canManageAcls(aCaller))
            throw new IllegalStateException("Only an admin may modify a shared ACL");
        return acls.setGrantById(anAclId, aKind, anAccessorId, flooredForOwner(aKind, aLevel), anExtPerms);
    }

    /**
     * Remove an accessor's grant from a named/shared ACL (admin only).
     *
     * @param aCaller      the acting user; must satisfy {@link #canManageAcls(User)}
     * @param anAclId      the named ACL's id
     * @param aKind        the accessor kind to remove
     * @param anAccessorId the accessor id, or {@code null} for a special
     * @throws IllegalStateException if the caller may not manage ACLs
     */
    @Transactional
    public void removeSharedGrantById(final User aCaller, final String anAclId,
                                      final Ace.AccessorKind aKind, final String anAccessorId) {
        if (!canManageAcls(aCaller))
            throw new IllegalStateException("Only an admin may modify a shared ACL");
        acls.removeGrantById(anAclId, aKind, anAccessorId);
    }

    /**
     * Rename a named ACL's human label (admin only).
     *
     * @param aCaller the acting user; must satisfy {@link #canManageAcls(User)}
     * @param anAclId the named ACL's id
     * @param aLabel  the new human label
     * @return the renamed {@link Acl}, or {@code null} if unknown
     * @throws IllegalStateException if the caller may not manage ACLs
     */
    @Transactional
    public Acl renameAcl(final User aCaller, final String anAclId, final String aLabel) {
        if (!canManageAcls(aCaller))
            throw new IllegalStateException("Only an admin may rename a shared ACL");
        return acls.updateLabel(anAclId, aLabel);
    }

    /**
     * Create a new named ACL and attach it as an org member group's default (admin only) — the
     * hand-crafted alternative to the auto-provisioned org default. The prior default (if any) is
     * simply no longer referenced.
     *
     * @param aCaller  the acting user; must satisfy {@link #canManageAcls(User)}
     * @param aGroupId the org member group to attach the new ACL to
     * @param aLabel   the human label for the new ACL
     * @return the created {@link Acl}
     * @throws IllegalStateException if the caller may not manage ACLs
     */
    @Transactional
    public Acl createOrgAcl(final User aCaller, final String aGroupId, final String aLabel) {
        if (!canManageAcls(aCaller))
            throw new IllegalStateException("Only an admin may create a shared ACL");
        final Acl acl = acls.createNamedAcl(aLabel, null);
        acls.attachToGroup(aGroupId, acl.getId());
        return acl;
    }

    /**
     * Enforce the owner floor when persisting a grant: an {@code owner} ACE is never stored below
     * {@link BasicLevel#OWNER_FLOOR} ({@code read}). Non-owner accessors pass through unchanged.
     *
     * @param aKind  the accessor kind the grant is for
     * @param aLevel the requested basic level
     * @return {@code aLevel} raised to {@link BasicLevel#OWNER_FLOOR} when {@code aKind} is
     *         {@link Ace.AccessorKind#owner} and the request is below it; otherwise {@code aLevel}
     */
    private static BasicLevel flooredForOwner(final Ace.AccessorKind aKind, final BasicLevel aLevel) {
        if (aKind == Ace.AccessorKind.owner && aLevel != null && !aLevel.atLeast(BasicLevel.OWNER_FLOOR))
            return BasicLevel.OWNER_FLOOR;
        return aLevel;
    }

    // ---- Channel claim (deferred org provisioning) -------------------------

    /**
     * Provision a channel's member group + org default ACL, WITHOUT adding any member
     * (docs/access-control.md §4). Until this runs the channel's comments inherit the SYSTEM
     * default (fewer ACLs); this switches them to the ORG default
     * ({@code world NONE, owner DELETE, <member-group> WRITE}) and points the group's
     * {@code default_acl_id} at it. Idempotent. The group is empty — nobody can act on the
     * channel's comments via it until members are added (or the channel is claimed). Used by
     * admin/dev tooling to bring a channel under an org policy ahead of a personal claim.
     *
     * @param aChannelAccount the channel (system) account whose comments to govern
     * @return the channel's member group
     */
    @Transactional
    public AccessGroup provisionChannel(final User aChannelAccount) {
        final AccessGroup group = groups.findOrCreateChannelMemberGroup(aChannelAccount.getId());
        final Acl acl = acls.findOrCreateChannelCommentsAcl(
            aChannelAccount.getId(), group.getId(), aChannelAccount.getDisplayName());
        if (group.getDefaultAclId() == null) group.setDefaultAclId(acl.getId());
        return group;
    }

    /**
     * Claim a channel: {@link #provisionChannel} it, then make the claimant the founding
     * member/group admin (docs/access-control.md §4). Idempotent — re-claiming (e.g. a teammate)
     * just adds membership. Call from the real "claim your channel" flow.
     *
     * @param aClaimant       the user claiming the channel (becomes founding member)
     * @param aChannelAccount the channel (system) account being claimed
     * @return the channel's member group
     */
    @Transactional
    public AccessGroup claimChannel(final User aClaimant, final User aChannelAccount) {
        final AccessGroup group = provisionChannel(aChannelAccount);
        if (group.getOwnerAccessorId() == null) group.setOwnerAccessorId(aClaimant.getId());
        groups.addMember(group.getId(), aClaimant.getId());   // claimant is a user accessor
        return group;
    }

    // ---- Private study (notes / drafts) ------------------------------------

    /**
     * May the caller read another principal's PRIVATE content (notes, unpublished drafts)?
     * Only the owner themselves, or a superuser (inherits ≥ owner). No other tier has a
     * read path — the privacy promise holds for everyone below Super (§5).
     */
    public boolean canAccessPrivate(final User aCaller, final String anOwnerUserId) {
        if (aCaller == null || !aCaller.isActive() || anOwnerUserId == null) return false;
        return aCaller.getRole() == Role.superuser || anOwnerUserId.equals(aCaller.getId());
    }

    // ---- System capabilities (role plane) ----------------------------------

    public boolean canGrantContributor(final User aCaller) {   // admin+
        return aCaller != null && aCaller.isActive() && aCaller.getRole().atLeast(Role.admin);
    }

    public boolean canGrantAdmin(final User aCaller) {          // superuser only
        return aCaller != null && aCaller.isActive() && aCaller.getRole() == Role.superuser;
    }
}
