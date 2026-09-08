// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.repository;

import org.religioustext.app.model.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Spring Data access to {@link User} rows, keyed by the typed {@code usr-} id. Beyond the
 * inherited CRUD, it exposes the email- and token-based lookups the auth flows need
 * (login, email verification, password reset).
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.1.0
 */
public interface UserRepository extends JpaRepository<User, String> {

    /**
     * Find a user by email, case-insensitively (the login / uniqueness lookup).
     *
     * @param anEmail the email address to match (case-insensitive)
     * @return the matching user, or {@link Optional#empty()} if none
     */
    Optional<User> findByEmailIgnoreCase(final String anEmail);

    /**
     * Whether a user already exists with this email (case-insensitive) — the registration guard.
     *
     * @param anEmail the email address to test (case-insensitive)
     * @return {@code true} if a user with that email exists
     */
    boolean existsByEmailIgnoreCase(final String anEmail);

    /**
     * Find the user holding this email-verification token.
     *
     * @param aToken the verification token from the emailed link
     * @return the matching user, or {@link Optional#empty()} if the token is unknown/spent
     */
    Optional<User> findByEmailVerificationToken(final String aToken);

    /**
     * Find the user holding this password-reset token.
     *
     * @param aToken the reset token from the emailed link
     * @return the matching user, or {@link Optional#empty()} if the token is unknown/spent
     */
    Optional<User> findByPasswordResetToken(final String aToken);

    /**
     * Mark a user verified and clear their verification token in one statement (idempotent: a
     * spent token matches no row). The JPQL {@code :token} parameter is bound via {@link Param}
     * so it stays decoupled from the Java parameter name.
     *
     * @param aToken the verification token to consume
     * @return the number of rows updated (1 on success, 0 if the token was unknown/already spent)
     */
    @Modifying
    @Query("UPDATE User u SET u.verified = true, u.emailVerificationToken = NULL WHERE u.emailVerificationToken = :token")
    int verifyByToken(@Param("token") final String aToken);
}
