// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import org.religioustext.app.model.user.Role;
import org.religioustext.app.model.user.User;
import org.religioustext.app.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The account's way out: download everything the site holds about the user, or delete the account.
 *
 * <p>Export is a single JSON document — profile, reader preferences, private notes, highlights, saved
 * searches, comments (with their references) and API-key metadata. Key HASHES are never
 * exported; they are credentials, not the user's content.
 *
 * <p>Deletion removes the user row, and with it — through the schema's ON DELETE CASCADE —
 * their comments (public ones too), notes, saved searches and preferences. Three things point at
 * the user WITHOUT cascading and are removed here first: API keys (and their usage rows),
 * group memberships and per-user ACL entries. Accounts that carry platform duties — system
 * (channel) accounts and admins — cannot self-delete.
 *
 * <p>Meilisearch holds a derived copy of comments; a deleted comment leaves it at the next
 * reindex, which prod owns.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
@Service
public class AccountDataService {

    @PersistenceContext
    private EntityManager em;

    private final UserRepository  users;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper    mapper;

    public AccountDataService(final UserRepository aUsers, final PasswordEncoder aPasswordEncoder,
                              final ObjectMapper aMapper) {
        this.users           = aUsers;
        this.passwordEncoder = aPasswordEncoder;
        this.mapper          = aMapper;
    }

    /** Pretty-printed JSON of everything held for this account, as UTF-8 bytes. */
    @Transactional(readOnly = true)
    public byte[] exportJson(final String anEmail) {
        final User user = requireUser(anEmail);
        final String id = user.getId();

        final Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("exportedAt", LocalDateTime.now().toString());
        doc.put("profile", one("SELECT email, display_name AS displayName, created_at AS createdAt "
            + "FROM users WHERE id = :id", id));
        doc.put("preferences", one("SELECT default_source AS defaultSource, default_mode AS defaultMode, "
            + "default_order AS defaultOrder, show_comments_panel AS showCommentsPanel, "
            + "show_comment_markers AS showCommentMarkers, show_xref_markers AS showXrefMarkers, "
            + "show_highlights AS showHighlights, "
            + "resume_enabled AS resumeEnabled, ui_language AS uiLanguage FROM user_preferences "
            + "WHERE user_id = :id", id));
        doc.put("notes", rows("SELECT book_code AS bookCode, chapter, verse, content, source_id AS sourceId, "
            + "created_at AS createdAt, updated_at AS updatedAt FROM personal_notes WHERE user_id = :id "
            + "ORDER BY book_code, chapter, verse", id));
        doc.put("highlights", rows("SELECT book_code AS bookCode, chapter, verse, colour, "
            + "created_at AS createdAt FROM highlights WHERE user_id = :id "
            + "ORDER BY book_code, chapter, verse", id));
        doc.put("savedSearches", rows("SELECT query_text AS query, created_at AS createdAt "
            + "FROM saved_searches WHERE user_id = :id ORDER BY created_at", id));

        final List<Map<String, Object>> comments = rows("SELECT id, public_id AS publicId, content, "
            + "is_public AS isPublic, moderation_status AS moderationStatus, created_at AS createdAt, "
            + "updated_at AS updatedAt FROM comments WHERE user_id = :id ORDER BY created_at", id);
        for (final Map<String, Object> c : comments) {
            c.put("references", rows("SELECT ref_type AS type, source_id AS sourceId, book_code AS bookCode, "
                + "chapter, verse, url, label, description FROM comment_references "
                + "WHERE comment_id = :id ORDER BY position", String.valueOf(c.get("id"))));
        }
        doc.put("comments", comments);
        doc.put("apiKeys", rows("SELECT key_id AS keyId, label, created_at AS createdAt, "
            + "last_used_at AS lastUsedAt FROM api_keys WHERE user_id = :id", id));
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc);
        } catch (final Exception ex) {
            throw new IllegalStateException("Could not serialise account export", ex);
        }
    }

    /**
     * Permanently deletes the account after re-checking the password.
     *
     * @throws UserService.WrongPasswordException if the password does not match.
     * @throws AccountProtectedException          for system accounts and admins.
     */
    @Transactional
    public void deleteAccount(final String anEmail, final String aPassword) {
        final User user = requireUser(anEmail);
        if (aPassword == null || !passwordEncoder.matches(aPassword, user.getPasswordHash()))
            throw new UserService.WrongPasswordException();
        if (user.isSystem() || user.getRole().atLeast(Role.admin))
            throw new AccountProtectedException();

        final String id = user.getId();
        update("DELETE FROM api_usage WHERE api_key_id IN (SELECT id FROM api_keys WHERE user_id = :id)", id);
        update("DELETE FROM api_keys WHERE user_id = :id", id);
        update("DELETE FROM group_memberships WHERE member_accessor_id = :id", id);
        update("DELETE FROM aces WHERE accessor_kind = 'user' AND accessor_id = :id", id);
        update("DELETE FROM users WHERE id = :id", id);   // cascades comments, notes, searches, prefs
    }

    private User requireUser(final String anEmail) {
        return users.findByEmailIgnoreCase(anEmail == null ? "" : anEmail.trim())
            .orElseThrow(() -> new IllegalStateException("No user for email: " + anEmail));
    }

    private void update(final String aSql, final String anId) {
        em.createNativeQuery(aSql).setParameter("id", anId).executeUpdate();
    }

    private Map<String, Object> one(final String aSql, final String anId) {
        final List<Map<String, Object>> r = rows(aSql, anId);
        return r.isEmpty() ? Map.of() : r.get(0);
    }

    private List<Map<String, Object>> rows(final String aSql, final String anId) {
        final List<Tuple> found = em.createNativeQuery(aSql, Tuple.class)
            .setParameter("id", anId).getResultList();
        final List<Map<String, Object>> out = new ArrayList<>();
        for (final Tuple t : found) {
            final Map<String, Object> row = new LinkedHashMap<>();
            for (final var el : t.getElements()) row.put(el.getAlias(), plain(t.get(el)));
            out.add(row);
        }
        return out;
    }

    /** Timestamps as ISO strings, so the JSON carries no epoch numbers. */
    private static Object plain(final Object aValue) {
        if (aValue instanceof Timestamp ts) return ts.toLocalDateTime().toString();
        if (aValue instanceof LocalDateTime dt) return dt.toString();
        return aValue;
    }

    /** Thrown when an account carries platform duties and so cannot self-delete. */
    public static class AccountProtectedException extends RuntimeException {
        public AccountProtectedException() {
            super("System accounts and admins cannot delete themselves");
        }
    }
}
