package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.AccessGroup;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.model.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * DEV / TEST ONLY seed of real login users, groups, nested membership, and a claimed channel so
 * the V11 access-control model can be exercised in a running app. Gated on
 * {@code religioustext.dev.seed-testdata=true} — never runs in prod. Idempotent, and ordered
 * after {@link DataSeeder} (which creates the channel accounts this claims).
 *
 * <p>What it creates:
 * <ul>
 *   <li>Four login users, one per {@link Role} (consumer/contributor/admin/superuser), all with
 *       password {@value #TEST_PASSWORD}, verified and active.</li>
 *   <li>A claimed channel: the contributor claims the first channel account via the real
 *       {@link AccessService#claimChannel(User, User)} — creating its member group and org default
 *       ACL ({@code world NONE, owner DELETE, org WRITE}); the admin is added as a second member.</li>
 *   <li>A nesting case: a {@code test-subteam} group nested under that member group with the
 *       consumer in it, so the consumer inherits the org's {@code write} grant TRANSITIVELY.</li>
 *   <li>DEV-ONLY: every OTHER channel is {@link AccessService#provisionChannel(User) provisioned}
 *       (member group + org default ACL, no members) so the admin Access-control page lists them
 *       all. Prod stays deferred — real channels provision only on claim.</li>
 * </ul>
 *
 * Expected results to test: superuser does everything; admin can moderate any comment (role
 * plane); contributor and (via nesting) consumer can edit the claimed channel's comments;
 * everyone else is read-only there.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
@Service
@ConditionalOnProperty(name = "religioustext.dev.seed-testdata", havingValue = "true")
public class DevTestDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevTestDataSeeder.class);

    /** Shared password for every seeded test account (dev only). */
    public static final String TEST_PASSWORD = "test-1234";

    @PersistenceContext
    private EntityManager em;

    private final PasswordEncoder passwordEncoder;
    private final AccessService access;
    private final GroupService groups;

    public DevTestDataSeeder(final PasswordEncoder aPasswordEncoder, final AccessService anAccessService,
                             final GroupService aGroupService) {
        this.passwordEncoder = aPasswordEncoder;
        this.access = anAccessService;
        this.groups = aGroupService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(200)   // after DataSeeder (@Order 100): the channel accounts must exist to claim one
    @Transactional
    public void seed() {
        log.warn("DEV TEST DATA seeding is ON (religioustext.dev.seed-testdata=true) — NOT for prod.");

        final User consumer    = findOrCreateUser("consumer@test.local",    "Test Consumer",    Role.consumer);
        final User contributor = findOrCreateUser("contributor@test.local", "Test Contributor", Role.contributor);
        final User admin       = findOrCreateUser("admin@test.local",       "Test Admin",       Role.admin);
        final User superuser   = findOrCreateUser("super@test.local",       "Test Super",       Role.superuser);

        final User channel = firstChannelAccount();
        if (channel == null) {
            log.warn("No channel accounts found — DataSeeder hasn't populated any; skipping claim + nesting.");
        } else {
            // Contributor claims the channel → member group + org default ACL.
            final AccessGroup orgGroup = access.claimChannel(contributor, channel);
            // Admin joins the org too (a second member).
            groups.addMember(orgGroup.getId(), admin.getId());
            // Nesting: sub-team nested under the org, with the consumer in it → the consumer
            // transitively belongs to the org group and inherits its WRITE grant.
            final AccessGroup subTeam = findOrCreateGroup("test-subteam");
            groups.addMemberGroup(orgGroup.getId(), subTeam.getId());
            groups.addMember(subTeam.getId(), consumer.getId());
            log.warn("Claimed channel '{}' for {}; nested '{}' (member {}) under its member group.",
                channel.getDisplayName(), contributor.getEmail(), subTeam.getName(), consumer.getEmail());
        }

        // DEV ONLY: bring EVERY channel under an org policy (member group + org default ACL) so the
        // admin Access-control page shows them all. Prod stays DEFERRED — channels only provision on
        // a real claim. Idempotent, and the first channel above is already provisioned by its claim.
        int provisioned = 0;
        for (final User c : allChannelAccounts()) {
            access.provisionChannel(c);
            provisioned++;
        }
        log.warn("DEV: provisioned member group + org ACL for {} channel account(s).", provisioned);

        log.warn("TEST LOGINS (password '{}'): {} / {} / {} / {}", TEST_PASSWORD,
            consumer.getEmail(), contributor.getEmail(), admin.getEmail(), superuser.getEmail());
    }

    /** Find (or create + persist) a verified, active login user with the given role. Idempotent;
     *  keeps the role in sync on a re-run. */
    private User findOrCreateUser(final String anEmail, final String aDisplayName, final Role aRole) {
        final List<User> found = em.createQuery(
                "SELECT u FROM User u WHERE u.email = :e", User.class)
            .setParameter("e", anEmail).setMaxResults(1).getResultList();
        if (!found.isEmpty()) {
            final User existing = found.get(0);
            existing.setRole(aRole);
            return existing;
        }
        final User u = new User();
        u.setEmail(anEmail);
        u.setDisplayName(aDisplayName);
        u.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        u.setVerified(true);
        u.setActive(true);
        u.setRole(aRole);
        em.persist(u);
        return u;
    }

    /** Find (or create + persist) a plain (non-system) named group. */
    private AccessGroup findOrCreateGroup(final String aName) {
        final List<AccessGroup> found = em.createQuery(
                "SELECT g FROM AccessGroup g WHERE g.name = :n", AccessGroup.class)
            .setParameter("n", aName).setMaxResults(1).getResultList();
        if (!found.isEmpty()) return found.get(0);
        final AccessGroup g = new AccessGroup();
        g.setName(aName);
        g.setSystem(false);
        em.persist(g);
        return g;
    }

    /** The first channel (system) account by display name, or {@code null} if none exist. */
    private User firstChannelAccount() {
        return em.createQuery(
                "SELECT u FROM User u WHERE u.system = true ORDER BY u.displayName", User.class)
            .setMaxResults(1).getResultList().stream().findFirst().orElse(null);
    }

    /** Every channel (system) account, by display name. */
    private List<User> allChannelAccounts() {
        return em.createQuery(
                "SELECT u FROM User u WHERE u.system = true ORDER BY u.displayName", User.class)
            .getResultList();
    }
}
