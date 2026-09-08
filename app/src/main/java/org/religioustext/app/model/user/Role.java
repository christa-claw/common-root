package org.religioustext.app.model.user;

/**
 * The system-wide role ladder (see docs/access-control.md §1). Totally ordered and
 * cumulative — every role can do everything the role below it can, plus a little more:
 *
 * <pre>consumer  ⊂  contributor  ⊂  admin  ⊂  superuser</pre>
 *
 * Declaration order IS the privilege order, so {@link #atLeast(Role)} compares by
 * {@code ordinal()}. Constants are lowercase to mirror the MySQL {@code ENUM} (the same
 * convention {@link Comment.ModerationStatus} uses); the top tier is spelled
 * {@code superuser} because {@code super} is a Java keyword.
 *
 * <ul>
 *   <li><b>consumer</b> — default for every account: view content, create private notes
 *       and private (unpublished) comments. The old {@code USER}.</li>
 *   <li><b>contributor</b> — may publish comments (auto-approved, subject to the
 *       {@code BlockedDomain} check).</li>
 *   <li><b>admin</b> — grant/revoke contributor; publish/unpublish any comment.</li>
 *   <li><b>superuser</b> — grant/revoke admin; sees and does everything: full control
 *       ({@code delete}) on every object, always ≥ the owner's privilege.</li>
 * </ul>
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
public enum Role {
    consumer,
    contributor,
    admin,
    superuser;

    /**
     * Whether this role is at least as privileged as {@code anotherRole} on the ladder
     * ({@code consumer < contributor < admin < superuser}).
     *
     * @param anotherRole the role to compare against
     * @return {@code true} if {@code this} is {@literal >=} {@code anotherRole}
     */
    public boolean atLeast(final Role anotherRole) {
        return this.ordinal() >= anotherRole.ordinal();
    }

    /**
     * The Spring Security authority name for this rung.
     *
     * @return the authority, e.g. {@code "ROLE_CONTRIBUTOR"} — {@code "ROLE_"} + the
     *         upper-cased constant name
     */
    public String authority() {
        return "ROLE_" + name().toUpperCase();
    }
}
