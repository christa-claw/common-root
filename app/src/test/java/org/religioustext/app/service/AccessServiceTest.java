package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.Acl;
import org.religioustext.app.model.user.BasicLevel;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.model.user.User;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permutation matrix for the two-plane resolution (docs/access-control.md §2).
 *
 * Deliberately MOCKITO-FREE: collaborators are hand-built test doubles (anonymous
 * subclasses overriding just the methods used), and entities are real objects with an
 * overridden {@code getId()}. This matches the repo's "pure, standalone" test principle and
 * makes the suite immune to the JVM version — Mockito/Byte Buddy can't instrument newer JDKs
 * (the original version errored on Java 25), and there's nothing to instrument here.
 *
 * Axes: role (consumer/contributor/admin/superuser) × ownership (owner/not) × comment kind
 * (user-authored vs channel/system) × the ACL-plane level (stubbed via resolveLevel) × active.
 */
class AccessServiceTest {

    // ---- configurable doubles (set per test) --------------------------------
    private List<String> callerGroupIds = List.of();
    private AccessGroup orgGroup = null;                 // returned by findChannelMemberGroup
    private BasicLevel aclLevel = BasicLevel.none;       // returned by the ACL-plane chain

    private final GroupService groups = new GroupService() {
        @Override public List<String> groupIdsForUser(final String userId) { return callerGroupIds; }
        @Override public AccessGroup findChannelMemberGroup(final String channelUserId) { return orgGroup; }
    };
    private BasicLevel lastGrantLevel = null;            // captures the level actually persisted
    private Acl systemDefaultAcl = null;                 // returned by findSystemDefault (bulk path)
    private final java.util.Map<String, Acl> aclById = new java.util.HashMap<>();
    private final AclService acls = new AclService() {
        @Override public BasicLevel resolveLevel(final String customAclId, final String orgDefaultAclId,
                                                 final Set<String> keys) {
            return aclLevel;
        }
        // The BULK evaluator loads ACL objects and evaluates with the REAL (pure)
        // effectiveLevel — only the lookups are stubbed.
        @Override public Acl findSystemDefault() { return systemDefaultAcl; }
        @Override public Acl findById(final String id) { return aclById.get(id); }
        // capture the level the mutators are called with (to assert the owner-floor clamp),
        // and return null so allowed-path gating tests don't touch a (null) EntityManager
        @Override public Ace grantForComment(final Comment c, final String orgAcl, final Ace.AccessorKind k,
                                             final String id, final BasicLevel l, final String x) {
            lastGrantLevel = l; return null;
        }
        @Override public Ace setGrant(final String name, final Ace.AccessorKind k, final String id,
                                      final BasicLevel l, final String x) {
            lastGrantLevel = l; return null;
        }
    };
    private final AccessService access = new AccessService(groups, acls);

    // ---- builders -----------------------------------------------------------
    private static User user(final String id, final Role role, final boolean active,
                             final boolean system) {
        final User u = new User() { @Override public String getId() { return id; } };
        u.setRole(role);
        u.setActive(active);
        u.setSystem(system);
        return u;
    }
    private static User person(final String id, final Role role) { return user(id, role, true, false); }
    private static User channelAccount(final String id) { return user(id, Role.consumer, false, true); }
    private static Comment ownedBy(final User owner) { final Comment c = new Comment(); c.setUser(owner); return c; }
    private static AccessGroup orgWithDefaultAcl(final String aclId) {
        final AccessGroup g = new AccessGroup();
        g.setDefaultAclId(aclId);
        return g;
    }
    private static Comment publicComment(final User owner, final String publicId) {
        final Comment c = ownedBy(owner);
        c.setPublicId(publicId);
        return c;
    }
    private static Ace aceOf(final Ace.AccessorKind kind, final String id, final BasicLevel level) {
        final Ace e = new Ace();
        e.setAccessorKind(kind);
        e.setAccessorId(id);
        e.setEntryType(Ace.EntryType.permit);
        e.setBasicLevel(level);
        return e;
    }
    private static Acl aclOf(final Ace... entries) {
        final Acl acl = new Acl();
        for (final Ace e : entries) acl.getEntries().add(e);
        return acl;
    }

    // ---- guards -------------------------------------------------------------
    @Test
    void nullsAreNone() {
        assertThat(access.effectiveLevel(null, ownedBy(person("u1", Role.admin)))).isEqualTo(BasicLevel.none);
        assertThat(access.effectiveLevel(person("u1", Role.admin), null)).isEqualTo(BasicLevel.none);
    }

    @Test
    void suspendedIsNone() {
        final User caller = user("u1", Role.admin, false, false);   // inactive
        assertThat(access.effectiveLevel(caller, ownedBy(person("u2", Role.consumer)))).isEqualTo(BasicLevel.none);
    }

    // ---- superuser inherits ≥ owner (delete) --------------------------------
    @Test
    void superuserAlwaysDelete() {
        final User su = person("su", Role.superuser);
        final Comment other = ownedBy(person("u2", Role.consumer));
        assertThat(access.effectiveLevel(su, other)).isEqualTo(BasicLevel.delete);
        assertThat(access.canDelete(su, other)).isTrue();
        assertThat(access.canEdit(su, other)).isTrue();
    }

    // ---- owner floor = read; default delete; reducible for retention -------
    @Test
    void ownerDefaultsToDeleteViaAcl() {
        // The org/system default owner ACE grants delete; the owner's default is full control.
        aclLevel = BasicLevel.delete;                          // owner ACE = delete (the default)
        final User me = person("me", Role.consumer);
        final Comment mine = ownedBy(me);
        assertThat(access.effectiveLevel(me, mine)).isEqualTo(BasicLevel.delete);
        assertThat(access.canDelete(me, mine)).isTrue();
        assertThat(access.canEdit(me, mine)).isTrue();
    }

    @Test
    void ownerFloorGuaranteesReadEvenWhenAclGrantsNothing() {
        // Even with the ACL contributing none, the owner never drops below the read floor.
        aclLevel = BasicLevel.none;
        final User me = person("me", Role.consumer);
        final Comment mine = ownedBy(me);
        assertThat(access.effectiveLevel(me, mine)).isEqualTo(BasicLevel.read);
        assertThat(access.canEdit(me, mine)).isFalse();        // read < write
        assertThat(access.canDelete(me, mine)).isFalse();
    }

    @Test
    void ownerReducedToReadForRetention() {
        // An admin lowered the owner ACE to read (retention): the owner keeps read, loses write/delete.
        aclLevel = BasicLevel.read;
        final User me = person("me", Role.consumer);
        final Comment mine = ownedBy(me);
        assertThat(access.effectiveLevel(me, mine)).isEqualTo(BasicLevel.read);
        assertThat(access.canEdit(me, mine)).isFalse();
        assertThat(access.canDelete(me, mine)).isFalse();
    }

    @Test
    void ownerGrantIsClampedToReadFloorOnWrite() {
        final User owner = person("o", Role.consumer);
        final Comment c = ownedBy(owner);
        // Trying to set the owner below the floor is clamped up to read when persisted…
        access.changeCommentGrant(owner, c, Ace.AccessorKind.owner, null, BasicLevel.none, null);
        assertThat(lastGrantLevel).isEqualTo(BasicLevel.read);
        // …while a non-owner accessor passes through unchanged.
        access.changeCommentGrant(owner, c, Ace.AccessorKind.world, null, BasicLevel.none, null);
        assertThat(lastGrantLevel).isEqualTo(BasicLevel.none);
    }

    // ---- role plane on someone else's user-authored comment -----------------
    @Test
    void consumerNonOwnerReadsViaSystemDefault() {
        aclLevel = BasicLevel.read;                             // system default: world = read
        final Comment other = ownedBy(person("u2", Role.consumer));
        final User consumer = person("u1", Role.consumer);
        assertThat(access.effectiveLevel(consumer, other)).isEqualTo(BasicLevel.read);
        assertThat(access.canEdit(consumer, other)).isFalse();
        assertThat(access.canDelete(consumer, other)).isFalse();
    }

    @Test
    void contributorNonOwnerReadsOnly() {
        aclLevel = BasicLevel.read;
        final Comment other = ownedBy(person("u2", Role.consumer));
        assertThat(access.effectiveLevel(person("u1", Role.contributor), other)).isEqualTo(BasicLevel.read);
    }

    @Test
    void adminNonOwnerGetsWriteNotDelete() {
        aclLevel = BasicLevel.read;
        final Comment other = ownedBy(person("u2", Role.consumer));
        final User admin = person("a", Role.admin);
        assertThat(access.effectiveLevel(admin, other)).isEqualTo(BasicLevel.write);   // write from the role plane
        assertThat(access.canEdit(admin, other)).isTrue();
        assertThat(access.canDelete(admin, other)).isFalse();   // write < delete
    }

    // ---- channel comments: ORG default ACL = {world NONE, owner DELETE, <org> WRITE} ----
    @Test
    void channelOrgMemberGetsWrite() {
        orgGroup = orgWithDefaultAcl("acl-1");
        aclLevel = BasicLevel.write;                            // org default: <org> = write
        final Comment channelComment = ownedBy(channelAccount("chan"));
        final User member = person("m", Role.contributor);
        assertThat(access.effectiveLevel(member, channelComment)).isEqualTo(BasicLevel.write);
        assertThat(access.canEdit(member, channelComment)).isTrue();
        assertThat(access.canDelete(member, channelComment)).isFalse();
    }

    @Test
    void channelNonMemberGetsNoneFromOrgDefault() {
        orgGroup = orgWithDefaultAcl("acl-1");
        aclLevel = BasicLevel.none;                            // org default: world = none
        final Comment channelComment = ownedBy(channelAccount("chan"));
        assertThat(access.effectiveLevel(person("x", Role.consumer), channelComment)).isEqualTo(BasicLevel.none);
    }

    @Test
    void channelOrgGrantIsChangeable() {
        orgGroup = orgWithDefaultAcl("acl-1");
        aclLevel = BasicLevel.delete;                          // an admin/owner raised <org> to delete
        final Comment channelComment = ownedBy(channelAccount("chan"));
        final User member = person("m", Role.contributor);
        assertThat(access.effectiveLevel(member, channelComment)).isEqualTo(BasicLevel.delete);
        assertThat(access.canDelete(member, channelComment)).isTrue();
    }

    @Test
    void channelWithoutOrgFallsBackToSystemDefaultRead() {
        orgGroup = null;                                       // org unidentifiable → system default
        aclLevel = BasicLevel.read;                           // system default: world = read
        final Comment channelComment = ownedBy(channelAccount("chan"));
        assertThat(access.effectiveLevel(person("m", Role.consumer), channelComment)).isEqualTo(BasicLevel.read);
    }

    @Test
    void channelAdminStillGetsWriteByRole() {
        orgGroup = orgWithDefaultAcl("acl-1");
        aclLevel = BasicLevel.none;                           // org world = none
        final Comment channelComment = ownedBy(channelAccount("chan"));
        assertThat(access.effectiveLevel(person("a", Role.admin), channelComment)).isEqualTo(BasicLevel.write);
    }

    @Test
    void commentCustomAclIsResolved() {
        // A comment carrying its own custom ACL routes through the ACL plane (custom-first).
        aclLevel = BasicLevel.write;
        final Comment c = ownedBy(person("o", Role.consumer));
        c.setAclId("acl-custom");                              // comment-private ACL
        final User nonOwner = person("x", Role.consumer);     // a non-owner: no owner floor applies
        assertThat(access.effectiveLevel(nonOwner, c)).isEqualTo(BasicLevel.write);
    }

    // ---- capability helpers -------------------------------------------------
    @Test
    void moderateNeedsAdmin() {
        assertThat(access.canModerate(person("c", Role.consumer))).isFalse();
        assertThat(access.canModerate(person("c", Role.contributor))).isFalse();
        assertThat(access.canModerate(person("a", Role.admin))).isTrue();
        assertThat(access.canModerate(person("s", Role.superuser))).isTrue();
        assertThat(access.canModerate(user("x", Role.admin, false, false))).isFalse(); // suspended
    }

    @Test
    void publishNeedsContributor() {
        assertThat(access.canPublish(person("c", Role.consumer))).isFalse();
        assertThat(access.canPublish(person("c", Role.contributor))).isTrue();
        assertThat(access.canPublish(person("a", Role.admin))).isTrue();
        assertThat(access.canPublish(user("x", Role.contributor, false, false))).isFalse();
    }

    @Test
    void publishOwnNeedsOwnershipAndContributor() {
        final User contributor = person("me", Role.contributor);
        final User consumer = person("me2", Role.consumer);
        assertThat(access.canPublishOwn(contributor, ownedBy(contributor))).isTrue();
        assertThat(access.canPublishOwn(consumer, ownedBy(consumer))).isFalse();
        assertThat(access.canPublishOwn(contributor, ownedBy(person("other", Role.contributor)))).isFalse();
    }

    @Test
    void changeAclIsAdminOrOwner() {
        final User owner = person("o", Role.consumer);
        final Comment c = ownedBy(owner);
        assertThat(access.canChangeAcl(owner, c)).isTrue();                          // owner
        assertThat(access.canChangeAcl(person("a", Role.admin), c)).isTrue();        // admin
        assertThat(access.canChangeAcl(person("s", Role.superuser), c)).isTrue();    // superuser
        assertThat(access.canChangeAcl(person("x", Role.contributor), c)).isFalse(); // neither
    }

    @Test
    void changeCommentGrantIsGatedByCanChangeAcl() {
        final User owner = person("o", Role.consumer);
        final Comment c = ownedBy(owner);
        // allowed for owner and admin (doubles no-op)
        access.changeCommentGrant(owner, c, Ace.AccessorKind.group, "grp-1", BasicLevel.write, null);
        access.changeCommentGrant(person("a", Role.admin), c, Ace.AccessorKind.group, "grp-1", BasicLevel.write, null);
        // denied for a non-owner non-admin
        assertThatThrownBy(() -> access.changeCommentGrant(person("x", Role.contributor), c,
                Ace.AccessorKind.group, "grp-1", BasicLevel.write, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void changeSharedAclGrantNeedsAdmin() {
        access.changeSharedAclGrant(person("a", Role.admin), "channel-comments:chan",
                Ace.AccessorKind.group, "grp-1", BasicLevel.write, null);            // ok
        assertThatThrownBy(() -> access.changeSharedAclGrant(person("o", Role.consumer),
                "channel-comments:chan", Ace.AccessorKind.group, "grp-1", BasicLevel.write, null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void accessPrivateIsOwnerOrSuperuser() {
        assertThat(access.canAccessPrivate(person("me", Role.consumer), "me")).isTrue();
        assertThat(access.canAccessPrivate(person("su", Role.superuser), "someone")).isTrue();
        assertThat(access.canAccessPrivate(person("a", Role.admin), "someone")).isFalse();
        assertThat(access.canAccessPrivate(person("c", Role.consumer), "someone")).isFalse();
    }

    // ---- bulk evaluation (effectiveLevels — drives the per-card ✎ affordance) ----

    @Test
    void bulkSuperuserGetsDeleteOnEverything() {
        final List<Comment> cs = List.of(
            publicComment(person("u1", Role.consumer), "p1"),
            publicComment(channelAccount("chan"), "p2"));
        final var out = access.effectiveLevels(person("su", Role.superuser), cs);
        assertThat(out).containsEntry("p1", BasicLevel.delete).containsEntry("p2", BasicLevel.delete);
    }

    @Test
    void bulkInactiveOrNullCallerGetsNone() {
        final List<Comment> cs = List.of(publicComment(person("u1", Role.consumer), "p1"));
        assertThat(access.effectiveLevels(null, cs)).containsEntry("p1", BasicLevel.none);
        assertThat(access.effectiveLevels(user("x", Role.admin, false, false), cs))
            .containsEntry("p1", BasicLevel.none);
    }

    @Test
    void bulkOwnerGetsDeleteViaSystemDefaultStrangerGetsRead() {
        // System default {world READ, owner DELETE} governs unclaimed content.
        systemDefaultAcl = aclOf(
            aceOf(Ace.AccessorKind.world, null, BasicLevel.read),
            aceOf(Ace.AccessorKind.owner, null, BasicLevel.delete));
        final User me = person("me", Role.consumer);
        final List<Comment> cs = List.of(publicComment(me, "mine"),
            publicComment(person("u2", Role.consumer), "theirs"));
        final var out = access.effectiveLevels(me, cs);
        assertThat(out).containsEntry("mine", BasicLevel.delete)
                       .containsEntry("theirs", BasicLevel.read);
    }

    @Test
    void bulkMemberGetsOrgGrantTransitively() {
        // Org default {world NONE, owner DELETE, group WRITE}; the caller is in the group.
        aclById.put("acl-org", aclOf(
            aceOf(Ace.AccessorKind.world, null, BasicLevel.none),
            aceOf(Ace.AccessorKind.owner, null, BasicLevel.delete),
            aceOf(Ace.AccessorKind.group, "grp-1", BasicLevel.write)));
        orgGroup = orgWithDefaultAcl("acl-org");
        callerGroupIds = List.of("grp-1");
        final var out = access.effectiveLevels(person("m", Role.consumer),
            List.of(publicComment(channelAccount("chan"), "c1")));
        assertThat(out).containsEntry("c1", BasicLevel.write);
    }

    @Test
    void bulkNonMemberGetsNoneFromOrgDefault() {
        aclById.put("acl-org", aclOf(
            aceOf(Ace.AccessorKind.world, null, BasicLevel.none),
            aceOf(Ace.AccessorKind.owner, null, BasicLevel.delete),
            aceOf(Ace.AccessorKind.group, "grp-1", BasicLevel.write)));
        orgGroup = orgWithDefaultAcl("acl-org");
        callerGroupIds = List.of();   // not a member
        final var out = access.effectiveLevels(person("x", Role.consumer),
            List.of(publicComment(channelAccount("chan"), "c1")));
        assertThat(out).containsEntry("c1", BasicLevel.none);
    }

    @Test
    void bulkCustomAclWinsOverChain() {
        aclById.put("acl-custom", aclOf(aceOf(Ace.AccessorKind.users, null, BasicLevel.write)));
        final Comment c = publicComment(person("o", Role.consumer), "c1");
        c.setAclId("acl-custom");
        final var out = access.effectiveLevels(person("x", Role.consumer), List.of(c));
        assertThat(out).containsEntry("c1", BasicLevel.write);
    }

    @Test
    void bulkAdminHoldsWriteFromRolePlane() {
        systemDefaultAcl = aclOf(aceOf(Ace.AccessorKind.world, null, BasicLevel.read));
        final var out = access.effectiveLevels(person("a", Role.admin),
            List.of(publicComment(person("u2", Role.consumer), "c1")));
        assertThat(out).containsEntry("c1", BasicLevel.write);
    }

    @Test
    void bulkSkipsCommentsWithoutPublicId() {
        final var out = access.effectiveLevels(person("su", Role.superuser),
            List.of(ownedBy(person("u1", Role.consumer))));   // no publicId
        assertThat(out).isEmpty();
    }
}
