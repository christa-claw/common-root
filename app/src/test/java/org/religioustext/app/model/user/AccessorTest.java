package org.religioustext.app.model.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** User and AccessGroup are both Accessors with the right kind (docs/access-control.md §2a). */
class AccessorTest {

    @Test
    void userIsAUserAccessor() {
        final Accessor u = new User();
        assertThat(u.accessorKind()).isEqualTo(Ace.AccessorKind.user);
    }

    @Test
    void groupIsAGroupAccessor() {
        final Accessor g = new AccessGroup();
        assertThat(g.accessorKind()).isEqualTo(Ace.AccessorKind.group);
    }
}
