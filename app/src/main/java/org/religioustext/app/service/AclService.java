package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.Acl;
import org.religioustext.app.model.user.BasicLevel;
import org.religioustext.app.model.user.Comment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Generic ACL-plane evaluator (docs/access-control.md §2): given an object's ACL and the
 * set of accessor keys a caller matches, compute the effective basic level by the
 * Documentum rule — MAX over permits, lowered by restrictions, gated by required entries.
 *
 * <p>An accessor key is a string the caller "is": {@code "world"}, {@code "users"},
 * {@code "owner"}, {@code "role:ROLE_ADMIN"}, {@code "user:<id>"}, or {@code "group:<id>"}.
 * Build the caller's key set once, pass it here.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Service
public class AclService {

    @PersistenceContext
    private EntityManager em;

    /** Conventional name of a channel's comments ACL. */
    public static String channelCommentsAclName(final String aChannelUserId) {
        return "channel-comments:" + aChannelUserId;
    }

    /** The system-wide default ACL — the last fallback for any object with no ACL of its own
     *  and no org default (docs/access-control.md §4). */
    public static final String SYSTEM_DEFAULT_ACL_NAME = "system-default";

    @Transactional(readOnly = true)
    public Acl findById(final String anId) {
        return anId == null ? null : em.find(Acl.class, anId);
    }

    /** The system default ACL, looked up by its reserved name {@value #SYSTEM_DEFAULT_ACL_NAME};
     *  {@code null} if it hasn't been seeded yet (see {@link #findOrCreateSystemDefaultAcl()}). */
    @Transactional(readOnly = true)
    public Acl findSystemDefault() {
        return findByName(SYSTEM_DEFAULT_ACL_NAME);
    }

    /** Named/shared ACLs (NOT per-object custom instances) — for the admin "edit ACLs" list. */
    @Transactional(readOnly = true)
    public List<Acl> listNamed() {
        return em.createQuery(
                "SELECT a FROM Acl a WHERE a.custom = false ORDER BY a.label, a.name", Acl.class)
            .getResultList();
    }

    /** Rename a named ACL (its human label — direct-edit metadata). */
    @Transactional
    public Acl updateLabel(final String anAclId, final String aLabel) {
        final Acl acl = findById(anAclId);
        if (acl != null) acl.setLabel(aLabel);
        return acl;
    }

    /**
     * Idempotently create the system default ACL (named {@value #SYSTEM_DEFAULT_ACL_NAME}) — a
     * conservative baseline: {@code world} {@literal ->} {@link BasicLevel#read};
     * {@code owner} {@literal ->} {@link BasicLevel#delete} (the owner's DEFAULT grant; the floor
     * itself is {@link BasicLevel#OWNER_FLOOR}, {@code read}). Seeded once at startup by
     * {@link DataSeeder}.
     */
    @Transactional
    public Acl findOrCreateSystemDefaultAcl() {
        final Acl existing = findByName(SYSTEM_DEFAULT_ACL_NAME);
        if (existing != null) return existing;
        final Acl acl = new Acl();
        acl.setName(SYSTEM_DEFAULT_ACL_NAME);
        acl.setLabel("System default — fallback when an object has no ACL and no org default");
        acl.setSystem(true);
        em.persist(acl);
        // System default (docs/access-control.md §4): world=READ, owner=DELETE. Used whenever
        // the org can't be identified. Admin power comes from the role plane, not an ACE.
        addAce(acl, Ace.AccessorKind.world, null, BasicLevel.read,   null, 0);
        addAce(acl, Ace.AccessorKind.owner, null, BasicLevel.delete, null, 1);
        return acl;
    }

    /**
     * Resolve the effective level for a caller (given their {@code theAccessorKeys}) through the
     * full ACL chain: the object's own CUSTOM ACL if any → its ORG default → the SYSTEM
     * default. Any link that's set-but-missing falls through to the next.
     */
    @Transactional(readOnly = true)
    public BasicLevel resolveLevel(final String aCustomAclId, final String anOrgDefaultAclId,
                                   final Set<String> theAccessorKeys) {
        return effectiveLevel(effectiveAclFor(aCustomAclId, anOrgDefaultAclId), theAccessorKeys);
    }

    /** The effective ACL OBJECT for an object (custom → org default → system default) — for
     *  display (the ACL editor). Mirrors {@link #resolveLevel} but returns the ACL, not a level. */
    @Transactional(readOnly = true)
    public Acl effectiveAclFor(final String aCustomAclId, final String anOrgDefaultAclId) {
        Acl acl = aCustomAclId != null ? findById(aCustomAclId) : null;
        if (acl == null && anOrgDefaultAclId != null) acl = findById(anOrgDefaultAclId);
        if (acl == null) acl = findSystemDefault();
        return acl;
    }

    /**
     * Idempotently provision a channel's comments ACL — the org default (docs/access-control.md
     * §4): {@code world NONE, owner DELETE, <member-group> WRITE}. The label follows the convention
     * "&lt;commenter organisation name&gt; default ACL".
     */
    @Transactional
    public Acl findOrCreateChannelCommentsAcl(final String aChannelUserId,
                                              final String aMemberGroupId,
                                              final String aChannelLabel) {
        final String name = channelCommentsAclName(aChannelUserId);
        final Acl existing = findByName(name);
        if (existing != null) return existing;

        final Acl acl = new Acl();
        acl.setName(name);                       // machine key
        acl.setLabel((aChannelLabel != null && !aChannelLabel.isBlank()
            ? aChannelLabel : "Unattributed") + " default ACL");
        acl.setSystem(true);
        acl.setOwnerAccessorId(aChannelUserId);  // the channel account (a user accessor)
        em.persist(acl);

        addAce(acl, Ace.AccessorKind.world, null,  BasicLevel.none,   null, 0);
        addAce(acl, Ace.AccessorKind.owner, null,  BasicLevel.delete, null, 1);
        addAce(acl, Ace.AccessorKind.group, aMemberGroupId, BasicLevel.write, null, 2); // the org; changeable
        return acl;
    }

    /**
     * MODE 1 — DIRECT edit of a shared ACL (by name). Affects EVERY object attached to it
     * (docs/access-control.md §4a). Creates the ACE if absent. Null if the ACL is unknown.
     */
    @Transactional
    public Ace setGrant(final String anAclName, final Ace.AccessorKind aKind,
                        final String anAccessorId, final BasicLevel aLevel, final String anExtPerms) {
        final Acl acl = findByName(anAclName);
        return acl == null ? null : setGrantOn(acl, aKind, anAccessorId, aLevel, anExtPerms);
    }

    /** MODE 1 by ACL id — upsert a permit ACE on a named/shared ACL. Null if unknown. */
    @Transactional
    public Ace setGrantById(final String anAclId, final Ace.AccessorKind aKind,
                            final String anAccessorId, final BasicLevel aLevel, final String anExtPerms) {
        final Acl acl = findById(anAclId);
        return acl == null ? null : setGrantOn(acl, aKind, anAccessorId, aLevel, anExtPerms);
    }

    /** MODE 1 by ACL id — remove an accessor's permit ACE from a named/shared ACL (orphanRemoval
     *  deletes the row on flush). No-op if the ACL or the entry is absent. */
    @Transactional
    public void removeGrantById(final String anAclId, final Ace.AccessorKind aKind, final String anAccessorId) {
        final Acl acl = findById(anAclId);
        if (acl == null) return;
        acl.getEntries().removeIf(e -> e.getEntryType() == Ace.EntryType.permit
            && e.getAccessorKind() == aKind && Objects.equals(e.getAccessorId(), anAccessorId));
    }

    /** Create a NEW named/shared ACL (not a per-object custom instance) with a conservative
     *  baseline of {@code world NONE, owner DELETE}. Its machine name is a generated unique slug;
     *  {@code aLabel} is the human name shown in the admin list. Attach it to something (e.g. an
     *  org via {@link #attachToGroup}) to give it effect. */
    @Transactional
    public Acl createNamedAcl(final String aLabel, final String anOwnerAccessorId) {
        final Acl acl = new Acl();
        acl.setName("named:" + UUID.randomUUID());
        acl.setLabel(aLabel == null || aLabel.isBlank() ? "New ACL" : aLabel.trim());
        acl.setCustom(false);
        acl.setSystem(false);
        acl.setOwnerAccessorId(anOwnerAccessorId);
        em.persist(acl);
        addAce(acl, Ace.AccessorKind.world, null, BasicLevel.none,   null, 0);
        addAce(acl, Ace.AccessorKind.owner, null, BasicLevel.delete, null, 1);
        return acl;
    }

    /** Point an org member group's {@code default_acl_id} at the given ACL — how a hand-crafted
     *  named ACL is put to use (its comments then resolve through it). No-op if the group is
     *  unknown. */
    @Transactional
    public void attachToGroup(final String aGroupId, final String anAclId) {
        final AccessGroup group = aGroupId == null ? null : em.find(AccessGroup.class, aGroupId);
        if (group != null) group.setDefaultAclId(anAclId);
    }

    /**
     * MODE 2 — edit security IN THE CONTEXT OF ONE COMMENT (copy-on-write). If the comment
     * already has its own custom ACL, modify that in place; otherwise clone the comment's
     * currently-effective ACL into a new custom ACL {@code "comment:<id>"}, attach it, and edit
     * the copy. Only this comment is affected. Returns the changed ACE.
     */
    @Transactional
    public Ace grantForComment(final Comment aComment, final String anOrgDefaultAclId,
                               final Ace.AccessorKind aKind, final String anAccessorId,
                               final BasicLevel aLevel, final String anExtPerms) {
        final Acl custom = ensureCustomCommentAcl(aComment, anOrgDefaultAclId);
        return setGrantOn(custom, aKind, anAccessorId, aLevel, anExtPerms);
    }

    /** Reclaim (minimise ACL count): drop a comment's CUSTOM ACL and re-inherit the org →
     *  system default. No-op if the comment already inherits. */
    @Transactional
    public void detachCommentAcl(final Comment aComment) {
        final String id = aComment.getAclId();
        if (id == null) return;
        aComment.setAclId(null);
        final Acl acl = findById(id);
        if (acl != null && acl.isCustom()) em.remove(acl);
    }

    /** Remove an accessor's grant from a comment (copy-on-write first if it was inherited).
     *  Acl.entries is orphanRemoval, so the dropped ACE is deleted on flush. */
    @Transactional
    public void removeGrantForComment(final Comment aComment, final String anOrgDefaultAclId,
                                      final Ace.AccessorKind aKind, final String anAccessorId) {
        final Acl custom = ensureCustomCommentAcl(aComment, anOrgDefaultAclId);
        custom.getEntries().removeIf(e -> e.getEntryType() == Ace.EntryType.permit
            && e.getAccessorKind() == aKind && Objects.equals(e.getAccessorId(), anAccessorId));
    }

    /** The comment's own custom ACL, creating it (copy-on-write) on first use. */
    @Transactional
    public Acl ensureCustomCommentAcl(final Comment aComment, final String anOrgDefaultAclId) {
        final String existingId = aComment.getAclId();
        if (existingId != null) {
            final Acl cur = findById(existingId);
            if (cur != null && !cur.isSystem()) return cur;   // already comment-private
        }
        final Acl source = existingId != null ? findById(existingId)
            : (anOrgDefaultAclId != null ? findById(anOrgDefaultAclId) : findSystemDefault());
        final Acl custom = cloneAcl(source, "comment:" + aComment.getId());
        aComment.setAclId(custom.getId());                    // managed entity → flushes
        return custom;
    }

    /** Deep-copy an ACL's entries into a new NON-system CUSTOM (instance) ACL — no label,
     *  hidden from the named-ACL admin list. */
    private Acl cloneAcl(final Acl aSource, final String aName) {
        final Acl acl = new Acl();
        acl.setName(aName);
        acl.setCustom(true);         // per-object instance ACL
        acl.setSystem(false);
        em.persist(acl);
        int order = 0;
        if (aSource != null)
            for (final Ace e : aSource.getEntries())
                addAce(acl, e.getAccessorKind(), e.getAccessorId(), e.getBasicLevel(),
                       e.getExtPerms(), order++);
        return acl;
    }

    /** Upsert a permit ACE on a specific ACL. */
    private Ace setGrantOn(final Acl anAcl, final Ace.AccessorKind aKind, final String anAccessorId,
                           final BasicLevel aLevel, final String anExtPerms) {
        for (final Ace e : anAcl.getEntries()) {
            if (e.getEntryType() == Ace.EntryType.permit
                    && e.getAccessorKind() == aKind
                    && Objects.equals(e.getAccessorId(), anAccessorId)) {
                e.setBasicLevel(aLevel);
                e.setExtPerms(anExtPerms);
                return e;
            }
        }
        return addAce(anAcl, aKind, anAccessorId, aLevel, anExtPerms, anAcl.getEntries().size());
    }

    private Ace addAce(final Acl anAcl, final Ace.AccessorKind aKind, final String anAccessorId,
                       final BasicLevel aLevel, final String anExtPerms, final int anOrder) {
        final Ace e = new Ace();
        e.setAcl(anAcl);
        e.setAccessorKind(aKind);
        e.setAccessorId(anAccessorId);
        e.setBasicLevel(aLevel);
        e.setExtPerms(anExtPerms);
        e.setSortOrder(anOrder);
        em.persist(e);
        anAcl.getEntries().add(e);
        return e;
    }

    @Transactional(readOnly = true)
    public Acl findByName(final String aName) {
        final List<Acl> found = em.createQuery(
                "SELECT a FROM Acl a WHERE a.name = :n", Acl.class)
            .setParameter("n", aName)
            .setMaxResults(1)
            .getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * Effective {@link BasicLevel} of a caller (described by their {@code theAccessorKeys} —
     * strings like {@code "world"}, {@code "role:ROLE_ADMIN"}, {@code "group:<id>"}) on an object
     * governed by {@code anAcl}. A {@code null} ACL yields {@link BasicLevel#none}. The rule:
     * {@snippet lang="text" :
     *   effective = MAX over permits whose accessor the caller matches
     *             , lowered by any matching restriction
     *             , and gated to none by any required accessor the caller lacks
     * }
     * @param anAcl           the governing ACL, or {@code null}
     * @param theAccessorKeys the set of accessor keys the caller matches (see {@link AccessService})
     * @return the effective basic level; never {@code null}
     */
    public BasicLevel effectiveLevel(final Acl anAcl, final Set<String> theAccessorKeys) {
        if (anAcl == null) return BasicLevel.none;

        // Required entries (AND-gate): if any names an accessor the caller lacks, deny.
        for (final Ace e : anAcl.getEntries()) {
            if (e.getEntryType() == Ace.EntryType.required
                    && !theAccessorKeys.contains(keyOf(e))) {
                return BasicLevel.none;
            }
        }

        BasicLevel granted = BasicLevel.none;
        BasicLevel restrictionCap = BasicLevel.delete; // no cap unless a restriction applies

        for (final Ace e : anAcl.getEntries()) {
            if (!theAccessorKeys.contains(keyOf(e))) continue;
            switch (e.getEntryType()) {
                case permit      -> granted = BasicLevel.max(granted, e.getBasicLevel());
                case restriction -> {
                    if (e.getBasicLevel().ordinal() < restrictionCap.ordinal())
                        restrictionCap = e.getBasicLevel();
                }
                case required    -> { /* handled above */ }
            }
        }
        return granted.ordinal() <= restrictionCap.ordinal() ? granted : restrictionCap;
    }

    /** The accessor key an ACE matches against (mirror the caller-key format). */
    private static String keyOf(final Ace anAce) {
        return switch (anAce.getAccessorKind()) {
            case world -> "world";
            case users -> "users";
            case owner -> "owner";
            case role  -> "role:" + anAce.getAccessorId();
            case user  -> "user:" + anAce.getAccessorId();
            case group -> "group:" + anAce.getAccessorId();
        };
    }
}
