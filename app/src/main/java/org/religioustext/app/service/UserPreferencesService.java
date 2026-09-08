// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.UserPreferences;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Per-user reader preferences (see {@link UserPreferences}). Email-keyed like
 * {@link PersonalNoteService} — the views only know the authenticated
 * principal's email; the user_id resolution stays in here.
 */
@Service
public class UserPreferencesService {

    @PersistenceContext
    private EntityManager em;

    /** The user's preferences row, or empty when signed out / never saved. */
    @Transactional(readOnly = true)
    public Optional<UserPreferences> find(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) return Optional.empty();
        final List<UserPreferences> rows = em.createQuery(
                "SELECT p FROM UserPreferences p WHERE p.userId = "
              + "(SELECT u.id FROM User u WHERE u.email = :email)",
                UserPreferences.class)
            .setParameter("email", anEmail)
            .setMaxResults(1).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Upsert: apply {@code mutate} to the user's row, creating it first if this
     *  is their first save. Returns the (managed) row. */
    @Transactional
    public UserPreferences save(final String anEmail, final Consumer<UserPreferences> aMutation) {
        final String userId = em.createQuery(
                "SELECT u.id FROM User u WHERE u.email = :email", String.class)
            .setParameter("email", anEmail)
            .setMaxResults(1).getResultList()
            .stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No user for email: " + anEmail));
        UserPreferences p = em.find(UserPreferences.class, userId);
        if (p == null) {
            p = new UserPreferences();
            p.setUserId(userId);
            aMutation.accept(p);
            em.persist(p);
        } else {
            aMutation.accept(p);   // managed — flush persists the change
        }
        return p;
    }

    /** Update the saved reading position — a targeted write on the hot path
     *  (fires as the reader scrolls chapter to chapter): one bulk update that
     *  touches nothing when resume is off. A reader with no preferences row yet
     *  gets one, resume on (the entity default) — the position is recorded from
     *  the first chapter, without a visit to Preferences. Bulk JPQL bypasses the
     *  entity's @PreUpdate, hence the explicit updatedAt. */
    @Transactional
    public void savePosition(final String anEmail, final String aPosition) {
        if (anEmail == null || anEmail.isBlank() || aPosition == null) return;
        final String pos = aPosition.length() > 2000 ? aPosition.substring(0, 2000) : aPosition;
        final int updated = em.createQuery(
                "UPDATE UserPreferences p SET p.lastPosition = :pos, p.updatedAt = :now "
              + "WHERE p.resumeEnabled = true AND p.userId = "
              + "(SELECT u.id FROM User u WHERE u.email = :email)")
            .setParameter("pos", pos)
            .setParameter("now", LocalDateTime.now())
            .setParameter("email", anEmail)
            .executeUpdate();
        if (updated == 0 && find(anEmail).isEmpty()) {
            try { save(anEmail, p -> p.setLastPosition(pos)); }
            catch (final IllegalStateException ignored) { /* no such user — nothing to record */ }
        }
    }
}
