package org.religioustext.app.model.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The role ladder is totally ordered and cumulative (docs/access-control.md §1). */
class RoleTest {

    @Test
    void ladderIsAscending() {
        assertThat(Role.consumer.ordinal()).isLessThan(Role.contributor.ordinal());
        assertThat(Role.contributor.ordinal()).isLessThan(Role.admin.ordinal());
        assertThat(Role.admin.ordinal()).isLessThan(Role.superuser.ordinal());
    }

    @Test
    void atLeastIsReflexiveAndCumulative() {
        assertThat(Role.admin.atLeast(Role.admin)).isTrue();
        assertThat(Role.superuser.atLeast(Role.consumer)).isTrue();
        assertThat(Role.admin.atLeast(Role.contributor)).isTrue();
    }

    @Test
    void atLeastRejectsHigher() {
        assertThat(Role.consumer.atLeast(Role.contributor)).isFalse();
        assertThat(Role.contributor.atLeast(Role.admin)).isFalse();
        assertThat(Role.admin.atLeast(Role.superuser)).isFalse();
    }

    @Test
    void authorityNames() {
        assertThat(Role.consumer.authority()).isEqualTo("ROLE_CONSUMER");
        assertThat(Role.superuser.authority()).isEqualTo("ROLE_SUPERUSER");
    }
}
