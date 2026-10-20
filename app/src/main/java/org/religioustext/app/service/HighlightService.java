// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.Highlight;
import org.religioustext.app.model.user.HighlightColours;
import org.religioustext.app.model.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Verse highlights — the second private per-verse primitive, beside {@link PersonalNoteService}.
 *
 * One colour per (user, verse), EDITION-INDEPENDENT: a highlight on John 3:16 belongs to the verse
 * and shows in every edition. PRIVATE: no method returns another user's highlights.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
@Service
public class HighlightService {

    @PersistenceContext
    private EntityManager em;

    /** The signed-in user's highlights for (bookCode, chapter), keyed by verse number string
     *  (matching VerseRef.getVerseNumber()) with the colour KEY as value. Empty for guests. */
    @Transactional(readOnly = true)
    public Map<String, String> forChapter(final String anEmail, final String aBookCode, final int aChapter) {
        if (anEmail == null || anEmail.isBlank() || aBookCode == null || aBookCode.isBlank())
            return Map.of();
        final List<Highlight> rows = em.createQuery(
                "SELECT h FROM Highlight h WHERE h.user.email = :email "
              + "AND h.bookCode = :code AND h.chapter = :ch", Highlight.class)
            .setParameter("email", anEmail).setParameter("code", aBookCode)
            .setParameter("ch", aChapter).getResultList();
        if (rows.isEmpty()) return Map.of();
        final Map<String, String> byVerse = new HashMap<>();
        for (final Highlight h : rows) byVerse.put(String.valueOf(h.getVerse()), h.getColour());
        return byVerse;
    }

    @Transactional(readOnly = true)
    public Optional<String> find(final String anEmail, final String aBookCode,
                                 final int aChapter, final int aVerse) {
        if (anEmail == null || anEmail.isBlank()) return Optional.empty();
        return findRow(anEmail, aBookCode, aChapter, aVerse).map(Highlight::getColour);
    }

    /**
     * Set the verse's colour. A blank or unknown colour removes the highlight; the same colour
     * again is a no-op (the editor decides whether that means "toggle off").
     */
    @Transactional
    public void set(final String anEmail, final String aBookCode, final int aChapter,
                    final int aVerse, final String aColour) {
        if (anEmail == null || anEmail.isBlank()) return;
        if (!HighlightColours.isValid(aColour)) { delete(anEmail, aBookCode, aChapter, aVerse); return; }

        final Optional<Highlight> existing = findRow(anEmail, aBookCode, aChapter, aVerse);
        if (existing.isPresent()) {
            existing.get().setColour(aColour);          // managed — flush persists the change
            return;
        }
        final User user = em.createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", anEmail).setMaxResults(1).getResultList()
            .stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No user for email: " + anEmail));
        final Highlight h = new Highlight();
        h.setUser(user);
        h.setBookCode(aBookCode);
        h.setChapter(aChapter);
        h.setVerse(aVerse);
        h.setColour(aColour);
        em.persist(h);
    }

    @Transactional
    public void delete(final String anEmail, final String aBookCode, final int aChapter, final int aVerse) {
        if (anEmail == null || anEmail.isBlank()) return;
        em.createQuery(
                "DELETE FROM Highlight h WHERE "
              + "h.user.id = (SELECT u.id FROM User u WHERE u.email = :email) "
              + "AND h.bookCode = :code AND h.chapter = :ch AND h.verse = :v")
            .setParameter("email", anEmail).setParameter("code", aBookCode)
            .setParameter("ch", aChapter).setParameter("v", aVerse).executeUpdate();
    }

    private Optional<Highlight> findRow(final String anEmail, final String aBookCode,
                                        final int aChapter, final int aVerse) {
        return em.createQuery(
                "SELECT h FROM Highlight h WHERE h.user.email = :email "
              + "AND h.bookCode = :code AND h.chapter = :ch AND h.verse = :v", Highlight.class)
            .setParameter("email", anEmail).setParameter("code", aBookCode)
            .setParameter("ch", aChapter).setParameter("v", aVerse)
            .setMaxResults(1).getResultList().stream().findFirst();
    }
}
