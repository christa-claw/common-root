// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.repository;

import org.religioustext.app.model.user.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data access to {@link ApiKey} rows. The hot lookup is by hash: the auth
 * filter hashes the presented credential and resolves it here — the plaintext key
 * is never a query parameter because it never exists at rest.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
public interface ApiKeyRepository extends JpaRepository<ApiKey, String> {

    /**
     * The auth-filter lookup: find a key row by the SHA-256 hex of the presented
     * credential. Revocation is checked by the caller ({@code isRevoked()}), not
     * the query, so a revoked key can still be distinguished from an unknown one
     * in logs without leaking that distinction to the caller.
     *
     * @param aKeyHash SHA-256 hex of the full presented key
     * @return the matching row, or empty if no such key was ever issued
     */
    Optional<ApiKey> findByKeyHash(final String aKeyHash);

    /**
     * All of one account's keys, live and revoked, newest first — the account
     * page listing.
     *
     * @param aUserId the owning account's {@code usr-} id
     * @return the account's keys ordered by creation, descending
     */
    List<ApiKey> findByUserIdOrderByCreatedAtDesc(final String aUserId);

    /**
     * How many live (unrevoked) keys an account holds — the creation cap check.
     *
     * @param aUserId the owning account's {@code usr-} id
     * @return the number of keys with no {@code revoked_at}
     */
    long countByUserIdAndRevokedAtIsNull(final String aUserId);
}
