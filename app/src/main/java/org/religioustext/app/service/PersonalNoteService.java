// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.PersonalNote;
import org.religioustext.app.model.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Personal study notes — the private half of the logged-in experience.
 *
 * One private note per (user, verse), EDITION-INDEPENDENT: a note on John 3:16
 * belongs to the verse itself and shows in every edition the reader opens, the
 * same rule V5 established for comment references. source_id is kept only as
 * optional provenance (which edition was open when the note was written); it is
 * not part of a note's identity.
 *
 * Mirrors {@link CommentQueryService}: EntityManager-backed, every value
 * materialised inside the transaction so the UI never touches a lazy proxy, and
 * verse-keyed maps so the reader can badge a verse exactly as it does comments.
 *
 * PRIVACY: a note is visible ONLY to its author. No method here returns another
 * user's notes, and notes never cross into the public comment/argument path.
 */
@Service
public class PersonalNoteService {

    @PersistenceContext
    private EntityManager em;

    /** The signed-in user's notes for (bookCode, chapter), keyed by verse number
     *  string (matching VerseRef.getVerseNumber()). Empty when signed out or
     *  when this chapter has none — so the reader fetches nothing for guests. */
    @Transactional(readOnly = true)
    public Map<String, String> forChapter(final String anEmail, final String aBookCode, final int aChapter) {
        if (anEmail == null || anEmail.isBlank() || aBookCode == null || aBookCode.isBlank())
            return Map.of();
        final List<PersonalNote> rows = em.createQuery(
                "SELECT n FROM PersonalNote n WHERE n.user.email = :email "
              + "AND n.bookCode = :code AND n.chapter = :ch", PersonalNote.class)
            .setParameter("email", anEmail)
            .setParameter("code", aBookCode)
            .setParameter("ch", aChapter)
            .getResultList();
        if (rows.isEmpty()) return Map.of();
        final Map<String, String> byVerse = new HashMap<>();
        for (final PersonalNote n : rows)
            byVerse.put(String.valueOf(n.getVerse()), n.getContent());
        return byVerse;
    }

    /** This user's note on a single verse, for the editor dialog (empty if none). */
    @Transactional(readOnly = true)
    public Optional<String> find(final String anEmail, final String aBookCode,
                                 final int aChapter, final int aVerse) {
        if (anEmail == null || anEmail.isBlank()) return Optional.empty();
        final List<PersonalNote> rows = em.createQuery(
                "SELECT n FROM PersonalNote n WHERE n.user.email = :email "
              + "AND n.bookCode = :code AND n.chapter = :ch AND n.verse = :v",
                PersonalNote.class)
            .setParameter("email", anEmail).setParameter("code", aBookCode)
            .setParameter("ch", aChapter).setParameter("v", aVerse)
            .setMaxResults(1).getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0).getContent());
    }

    /** Create or update this user's note on a verse. Blank content deletes it,
     *  so "clear the text and save" removes the note. sourceId is optional
     *  provenance (the edition open at write time), never part of identity. */
    @Transactional
    public void save(final String anEmail, final String aSourceId, final String aBookCode,
                     final int aChapter, final int aVerse, final String aContent) {
        if (aContent == null || aContent.isBlank()) { delete(anEmail, aBookCode, aChapter, aVerse); return; }

        final User user = em.createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", anEmail).setMaxResults(1).getResultList()
            .stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No user for email: " + anEmail));

        final List<PersonalNote> existing = em.createQuery(
                "SELECT n FROM PersonalNote n WHERE n.user.email = :email "
              + "AND n.bookCode = :code AND n.chapter = :ch AND n.verse = :v",
                PersonalNote.class)
            .setParameter("email", anEmail).setParameter("code", aBookCode)
            .setParameter("ch", aChapter).setParameter("v", aVerse)
            .setMaxResults(1).getResultList();

        if (existing.isEmpty()) {
            final PersonalNote n = new PersonalNote();
            n.setUser(user);
            n.setSourceId(aSourceId);
            n.setBookCode(aBookCode);
            n.setChapter(aChapter);
            n.setVerse(aVerse);
            n.setContent(aContent.strip());
            em.persist(n);
        } else {
            final PersonalNote n = existing.get(0);     // managed — flush persists the change
            n.setContent(aContent.strip());
            if (aSourceId != null && !aSourceId.isBlank()) n.setSourceId(aSourceId);
        }
    }

    /** Remove this user's note on a verse, if present. */
    @Transactional
    public void delete(final String anEmail, final String aBookCode,
                       final int aChapter, final int aVerse) {
        if (anEmail == null || anEmail.isBlank()) return;
        em.createQuery(
                "DELETE FROM PersonalNote n WHERE "
              + "n.user.id = (SELECT u.id FROM User u WHERE u.email = :email) "
              + "AND n.bookCode = :code AND n.chapter = :ch AND n.verse = :v")
            .setParameter("email", anEmail).setParameter("code", aBookCode)
            .setParameter("ch", aChapter).setParameter("v", aVerse)
            .executeUpdate();
    }
}
