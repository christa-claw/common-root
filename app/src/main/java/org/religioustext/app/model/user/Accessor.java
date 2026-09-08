// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

/**
 * A security principal that can appear in an ACL and can own objects — the Documentum
 * accessor. Both {@link User} and {@link AccessGroup} are accessors, which is what lets a
 * group (a) own things and (b) be a member of another group (nesting) exactly as a user can.
 *
 * The id is a {@link org.religioustext.app.util.TypedId} whose prefix already discriminates
 * ({@code usr-} vs {@code grp-}), so an "accessor id" is self-describing; {@link #accessorKind()}
 * makes that explicit for ACE matching.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
public interface Accessor {

    /**
     * This accessor's stable {@link org.religioustext.app.util.TypedId} — {@code usr-…} for a
     * {@link User}, {@code grp-…} for an {@link AccessGroup}. The prefix reveals the
     * {@link #accessorKind()}.
     *
     * @return the accessor id; never {@code null} for a persisted accessor
     */
    String getId();

    /**
     * Which kind of accessor this is: {@link Ace.AccessorKind#user} or
     * {@link Ace.AccessorKind#group} — the two entity-backed accessor kinds (the specials
     * {@link Ace.AccessorKind#world}, {@link Ace.AccessorKind#users}, {@link Ace.AccessorKind#owner}
     * and {@link Ace.AccessorKind#role} are not entities).
     *
     * @return this accessor's kind
     */
    Ace.AccessorKind accessorKind();
}
