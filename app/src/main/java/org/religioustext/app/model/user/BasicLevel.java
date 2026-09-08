// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

/**
 * The reduced Documentum permission ladder (docs/access-control.md §6), hierarchical so
 * "max over accessors" is just the higher {@code ordinal()}:
 *
 * <pre>none &lt; browse &lt; read &lt; write &lt; delete</pre>
 *
 * {@code delete} is the top basic level (implies write/read/browse). The OWNER FLOOR is
 * {@code read}: an owner can always at least READ their own object, but may be reduced from the
 * default {@code delete} down to (never below) {@code read} — e.g. an admin freezing an ingested
 * comment for retention. Additive extended permissions (moderate, change_acl, change_owner) live
 * outside this ladder, on {@link Ace#getExtPerms()}. Lowercase constants mirror the MySQL
 * {@code ENUM}.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
public enum BasicLevel {
    none,
    browse,
    read,
    write,
    delete;

    /** The owner floor — an owner holds at least this ({@link #read}) on their own object; they
     *  can be reduced to it (for retention) but never below it. */
    public static final BasicLevel OWNER_FLOOR = read;

    /**
     * Whether this level is at least as high as {@code anotherLevel} on the ladder
     * ({@code none < browse < read < write < delete}).
     *
     * @param anotherLevel the level to compare against
     * @return {@code true} if {@code this} is {@literal >=} {@code anotherLevel}
     */
    public boolean atLeast(final BasicLevel anotherLevel) {
        return this.ordinal() >= anotherLevel.ordinal();
    }

    /**
     * The higher of two levels — the "MAX over accessors" combinator the ACL evaluator uses.
     *
     * @param aLevel       one level
     * @param anotherLevel the other level
     * @return whichever of {@code aLevel} and {@code anotherLevel} is higher on the ladder
     */
    public static BasicLevel max(final BasicLevel aLevel, final BasicLevel anotherLevel) {
        return aLevel.ordinal() >= anotherLevel.ordinal() ? aLevel : anotherLevel;
    }
}
