// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.SavedSearch;
import org.religioustext.app.model.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saved full-text searches — a user's kept queries, re-run from the search dialog.
 *
 * Mirrors {@link PersonalNoteService}: EntityManager-backed, keyed by the signed-in
 * user's email, and PRIVATE — no method returns another user's searches. Saving
 * the same query twice is a no-op.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
@Service
public class SavedSearchService {

    /** Matches saved_searches.query_text. */
    static final int MAX_QUERY_LENGTH = 200;

    @PersistenceContext
    private EntityManager em;

    /** This user's saved queries, newest first. Empty when signed out. */
    @Transactional(readOnly = true)
    public List<String> list(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) return List.of();
        return em.createQuery(
                "SELECT s.queryText FROM SavedSearch s WHERE s.user.email = :email "
              + "ORDER BY s.createdAt DESC", String.class)
            .setParameter("email", anEmail)
            .getResultList();
    }

    @Transactional(readOnly = true)
    public boolean isSaved(final String anEmail, final String aQuery) {
        final String q = normalize(aQuery);
        if (anEmail == null || anEmail.isBlank() || q == null) return false;
        return !em.createQuery(
                "SELECT s.id FROM SavedSearch s WHERE s.user.email = :email AND s.queryText = :q",
                String.class)
            .setParameter("email", anEmail).setParameter("q", q)
            .setMaxResults(1).getResultList().isEmpty();
    }

    /** Keep a query. Blank or over-long queries are ignored; an already-saved one is a no-op. */
    @Transactional
    public void save(final String anEmail, final String aQuery) {
        final String q = normalize(aQuery);
        if (anEmail == null || anEmail.isBlank() || q == null || isSaved(anEmail, q)) return;
        final User user = em.createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", anEmail).setMaxResults(1).getResultList()
            .stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No user for email: " + anEmail));
        final SavedSearch s = new SavedSearch();
        s.setUser(user);
        s.setQueryText(q);
        em.persist(s);
    }

    /** Forget a query, if this user had saved it. */
    @Transactional
    public void delete(final String anEmail, final String aQuery) {
        final String q = normalize(aQuery);
        if (anEmail == null || anEmail.isBlank() || q == null) return;
        em.createQuery(
                "DELETE FROM SavedSearch s WHERE "
              + "s.user.id = (SELECT u.id FROM User u WHERE u.email = :email) AND s.queryText = :q")
            .setParameter("email", anEmail).setParameter("q", q)
            .executeUpdate();
    }

    private static String normalize(final String aQuery) {
        if (aQuery == null) return null;
        final String q = aQuery.strip();
        return q.isEmpty() || q.length() > MAX_QUERY_LENGTH ? null : q;
    }
}
