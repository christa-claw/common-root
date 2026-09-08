package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.Ace;
import org.religioustext.app.model.user.Acl;
import org.religioustext.app.model.user.BasicLevel;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure evaluation of the Documentum rule (docs/access-control.md §2): MAX over permits the
 * caller matches, lowered by restrictions, gated by required entries. No DB — the ACL and
 * its ACEs are built in memory and passed to {@link AclService#effectiveLevel}.
 */
class AclServiceTest {

    private final AclService acls = new AclService();

    // ---- helpers ----
    private static Acl acl(final Ace... entries) {
        final Acl a = new Acl();
        for (final Ace e : entries) a.getEntries().add(e);
        return a;
    }
    private static Ace ace(final Ace.AccessorKind kind, final String accessorId,
                           final Ace.EntryType type, final BasicLevel level) {
        final Ace e = new Ace();
        e.setAccessorKind(kind);
        e.setAccessorId(accessorId);
        e.setEntryType(type);
        e.setBasicLevel(level);
        return e;
    }
    private static Ace permit(final Ace.AccessorKind kind, final String id, final BasicLevel lvl) {
        return ace(kind, id, Ace.EntryType.permit, lvl);
    }

    @Test
    void nullAclIsNone() {
        assertThat(acls.effectiveLevel(null, Set.of("world"))).isEqualTo(BasicLevel.none);
    }

    @Test
    void unmatchedCallerGetsNone() {
        final Acl a = acl(permit(Ace.AccessorKind.group, "grp-1", BasicLevel.write));
        assertThat(acls.effectiveLevel(a, Set.of("world"))).isEqualTo(BasicLevel.none);
    }

    @Test
    void worldReadMatches() {
        final Acl a = acl(permit(Ace.AccessorKind.world, null, BasicLevel.read));
        assertThat(acls.effectiveLevel(a, Set.of("world"))).isEqualTo(BasicLevel.read);
    }

    @Test
    void ownerDeltaFloor() {
        final Acl a = acl(permit(Ace.AccessorKind.owner, null, BasicLevel.delete));
        assertThat(acls.effectiveLevel(a, Set.of("world", "owner"))).isEqualTo(BasicLevel.delete);
    }

    @Test
    void maxOverMatchedPermits() {
        final Acl a = acl(
            permit(Ace.AccessorKind.world, null, BasicLevel.read),
            permit(Ace.AccessorKind.group, "grp-1", BasicLevel.write));
        // member of grp-1 → write (the higher of read/write)
        assertThat(acls.effectiveLevel(a, Set.of("world", "group:grp-1"))).isEqualTo(BasicLevel.write);
        // not a member → only world read
        assertThat(acls.effectiveLevel(a, Set.of("world"))).isEqualTo(BasicLevel.read);
    }

    @Test
    void legacyRoleAceNeverMatches() {
        // Privileges are granted to ACCESSORS only — the role ladder is the separate privilege
        // plane, and AccessService.accessorKeys emits no role keys. A legacy/hand-inserted role
        // ACE is therefore inert: it grants nothing to anyone.
        final Acl a = acl(permit(Ace.AccessorKind.role, "ROLE_ADMIN", BasicLevel.write));
        assertThat(acls.effectiveLevel(a, Set.of("world", "users", "owner", "group:grp-1")))
            .isEqualTo(BasicLevel.none);
    }

    @Test
    void restrictionCapsPermit() {
        final Acl a = acl(
            permit(Ace.AccessorKind.world, null, BasicLevel.delete),
            ace(Ace.AccessorKind.world, null, Ace.EntryType.restriction, BasicLevel.read));
        assertThat(acls.effectiveLevel(a, Set.of("world"))).isEqualTo(BasicLevel.read);
    }

    @Test
    void requiredGroupGates() {
        final Acl a = acl(
            ace(Ace.AccessorKind.group, "grp-1", Ace.EntryType.required, BasicLevel.none),
            permit(Ace.AccessorKind.world, null, BasicLevel.read));
        // missing the required group → denied entirely
        assertThat(acls.effectiveLevel(a, Set.of("world"))).isEqualTo(BasicLevel.none);
        // in the required group → the world read applies
        assertThat(acls.effectiveLevel(a, Set.of("world", "group:grp-1"))).isEqualTo(BasicLevel.read);
    }
}
