// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.CommentReference;
import org.religioustext.app.model.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The write side of user comments — create / edit / delete one's OWN comment on
 * a verse. The read side (badges, panel, dialogs) stays in
 * {@link CommentQueryService}; the seeded arguments stay in {@link DataSeeder}.
 *
 * Shape mirrors {@link PersonalNoteService}: email-keyed (views only know the
 * authenticated principal), EntityManager-backed, values materialised inside
 * the transaction. The UI treats comments like notes — ONE own comment per
 * verse, edited in place — though the schema permits more; {@code forVerse}
 * returns the oldest first so the editor consistently binds the same row.
 *
 * Anchoring is edition-independent (the V5 rule): the internal reference
 * carries (bookCode, chapter, verse); sourceId is provenance only. A comment
 * written with only verse references auto-approves on {@link Comment#makePublic()}
 * — the moderation queue exists for external links, which this form doesn't take.
 *
 * OWNERSHIP: the create/update/delete methods verify the comment belongs to the caller's
 * email before touching it. Editing ANOTHER accessor's comment goes only through the
 * ACL-gated shared paths ({@link #updateShared} / {@link #deleteShared}), which require the
 * caller's effective level on that comment (owner floor, member-group write, admin role) to
 * reach {@code write} / {@code delete} via {@link AccessService}.
 */
@Service
public class CommentAuthorService {

    /** One of the caller's own comments, as the editor needs it. */
    public record OwnComment(String id, String publicId, String content,
                             boolean isPublic, String moderationStatus,
                             String rejectionReason) { }

    @PersistenceContext
    private EntityManager em;

    private final AccessService access;

    public CommentAuthorService(final AccessService anAccessService) {
        this.access = anAccessService;
    }

    /** The caller's own comments anchored to (bookCode, chapter, verse), oldest
     *  first. Empty when signed out or none exist. */
    @Transactional(readOnly = true)
    public List<OwnComment> forVerse(final String anEmail, final String aBookCode,
                                     final int aChapter, final int aVerse) {
        if (anEmail == null || anEmail.isBlank() || aBookCode == null || aBookCode.isBlank())
            return List.of();
        return em.createQuery(
                "SELECT DISTINCT c FROM Comment c JOIN c.references r "
              + "WHERE c.user.email = :email AND r.refType = :internal "
              + "AND r.bookCode = :code AND r.chapter = :ch AND r.verse = :v "
              + "ORDER BY c.createdAt ASC", Comment.class)
            .setParameter("email", anEmail)
            .setParameter("internal", CommentReference.RefType.internal)
            .setParameter("code", aBookCode)
            .setParameter("ch", aChapter)
            .setParameter("v", aVerse)
            .getResultList().stream()
            .map(c -> new OwnComment(c.getId(), c.getPublicId(), c.getContent(),
                c.isPublic(), c.getModerationStatus().name(), c.getRejectionReason()))
            .toList();
    }

    /** Create the caller's comment on a verse. The single internal reference
     *  anchors it; blank content is rejected. Returns the new comment's id. */
    @Transactional
    public String create(final String anEmail, final String aSourceId, final String aBookCode,
                         final int aChapter, final int aVerse, final String aContent,
                         final boolean aMakePublic) {
        if (aContent == null || aContent.isBlank())
            throw new IllegalArgumentException("Empty comment");
        final User user = em.createQuery(
                "SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", anEmail).setMaxResults(1).getResultList()
            .stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No user for email: " + anEmail));

        final Comment c = new Comment();
        c.setUser(user);
        c.setContent(aContent.strip());
        if (aMakePublic) {
            // Publishing requires the contributor role (docs/access-control.md §1, §8).
            // A consumer may keep private drafts but cannot make them public.
            if (!access.canPublish(user))
                throw new IllegalStateException("Publishing a comment requires the contributor role");
            c.makePublic();                  // verse-only refs -> approved immediately
        }
        em.persist(c);
        em.persist(CommentReference.internal(c, 0, aSourceId, aBookCode, aChapter, aVerse));
        return c.getId();
    }

    /** Update the caller's OWN comment: content and public flag. A no-op with a
     *  thrown error if the comment doesn't exist or isn't theirs. */
    @Transactional
    public void update(final String anEmail, final String aCommentId,
                       final String aContent, final boolean aMakePublic) {
        if (aContent == null || aContent.isBlank())
            throw new IllegalArgumentException("Empty comment");
        final Comment c = owned(anEmail, aCommentId);
        c.setContent(aContent.strip());
        if (aMakePublic && !c.isVisiblePublicly()) {
            if (!access.canPublish(c.getUser()))
                throw new IllegalStateException("Publishing a comment requires the contributor role");
            c.makePublic();
        }
        if (!aMakePublic && c.isPublic())          c.makePrivate();
        c.setLocallyEdited(true);   // survives the reseed if the owner is a channel account
        // managed entity — flush persists; @PreUpdate stamps updatedAt
    }

    /** Delete the caller's OWN comment (references cascade via orphanRemoval). */
    @Transactional
    public void delete(final String anEmail, final String aCommentId) {
        final Comment comment = owned(anEmail, aCommentId);
        tombstoneIfSeeded(comment);
        em.remove(comment);
    }

    // ---- ACL-gated shared editing (docs/access-control.md §2) ----------------

    /**
     * BULK effective levels of the caller over the given comments — for a comment list to
     * decide which cards get edit/delete affordances in one round trip.
     *
     * @param anEmail      the signed-in user's email ({@code null}/blank → empty map)
     * @param thePublicIds the comments' stable public ids
     * @return {@link org.religioustext.app.model.user.BasicLevel} name keyed by public id
     * @see AccessService#effectiveLevels(User, List)
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, String> editableLevels(final String anEmail,
                                                        final List<String> thePublicIds) {
        if (anEmail == null || anEmail.isBlank() || thePublicIds == null || thePublicIds.isEmpty())
            return java.util.Map.of();
        final List<User> callers = em.createQuery(
                "SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", anEmail).setMaxResults(1).getResultList();
        if (callers.isEmpty()) return java.util.Map.of();
        final List<Comment> comments = em.createQuery(
                "SELECT c FROM Comment c JOIN FETCH c.user WHERE c.publicId IN :ids", Comment.class)
            .setParameter("ids", thePublicIds).getResultList();
        final java.util.Map<String, String> out = new java.util.HashMap<>();
        access.effectiveLevels(callers.get(0), comments)
            .forEach((id, level) -> out.put(id, level.name()));
        return out;
    }

    /**
     * Edit a comment's CONTENT via the ACL plane — the shared path for a member-group grantee
     * (or the owner/an admin) fixing up a channel's imported argument. Touches only the text:
     * the public flag and moderation state are deliberately left alone.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable public id
     * @param aContent  the new content (blank is rejected)
     * @throws IllegalArgumentException if {@code aContent} is blank
     * @throws IllegalStateException    if not signed in / unknown account / comment not found,
     *                                  or the caller's effective level is below {@code write}
     */
    @Transactional
    public void updateShared(final String anEmail, final String aPublicId, final String aContent) {
        if (aContent == null || aContent.isBlank())
            throw new IllegalArgumentException("Empty comment");
        final User caller = callerByEmail(anEmail);
        final Comment comment = byPublicId(aPublicId);
        if (!access.canEdit(caller, comment))
            throw new IllegalStateException("Not permitted to edit this comment");
        comment.setContent(aContent.strip());   // managed — flush persists; @PreUpdate stamps
        comment.setLocallyEdited(true);         // the reseed must never overwrite a human edit
    }

    /**
     * Delete a comment via the ACL plane (effective level {@code delete} — the owner, an org
     * grant raised to delete, or a superuser). References cascade via orphanRemoval.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable public id
     * @throws IllegalStateException if not signed in / unknown account / comment not found, or
     *                               the caller's effective level is below {@code delete}
     */
    @Transactional
    public void deleteShared(final String anEmail, final String aPublicId) {
        final User caller = callerByEmail(anEmail);
        final Comment comment = byPublicId(aPublicId);
        if (!access.canDelete(caller, comment))
            throw new IllegalStateException("Not permitted to delete this comment");
        tombstoneIfSeeded(comment);
        em.remove(comment);
    }

    /** A deleted SEEDED (system-account) comment must not resurrect at the next reseed —
     *  leave a tombstone the seeder checks. User-authored comments need none (never reseeded). */
    private void tombstoneIfSeeded(final Comment aComment) {
        if (aComment.getUser() != null && aComment.getUser().isSystem()
                && aComment.getPublicId() != null && !aComment.getPublicId().isBlank()
                && em.find(org.religioustext.app.model.user.CommentTombstone.class, aComment.getPublicId()) == null) {
            em.persist(new org.religioustext.app.model.user.CommentTombstone(aComment.getPublicId()));
        }
    }

    // ---- ACL-gated reference editing -----------------------------------------

    /** One editable internal (verse) reference of a comment. */
    public record EditableRef(String id, boolean quran, String bookCode, int chapter, int verse) { }

    /** A comment's editable external link (the watch/source URL), or absent. */
    public record EditableExternal(String id, String url, String label) { }

    /** The references of one comment, shaped for the editor dialog. */
    public record CommentRefs(List<EditableRef> verses, EditableExternal external) { }

    /**
     * The comment's references for the editor — gated like an edit (level {@code write}).
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable public id
     * @return internal verse refs (in position order) plus the first external link, if any
     * @throws IllegalStateException if not signed in / unknown account / comment not found /
     *                               below {@code write}
     */
    @Transactional(readOnly = true)
    public CommentRefs refsFor(final String anEmail, final String aPublicId) {
        final Comment comment = editable(anEmail, aPublicId);
        final List<EditableRef> verses = new java.util.ArrayList<>();
        EditableExternal external = null;
        for (final CommentReference r : comment.getReferences()) {
            if (r.getRefType() == CommentReference.RefType.internal) {
                if (r.getBookCode() == null || r.getChapter() == null || r.getVerse() == null) continue;
                verses.add(new EditableRef(r.getId(),
                    r.getBookCode().chars().allMatch(Character::isDigit),
                    r.getBookCode(), r.getChapter(), r.getVerse()));
            } else if (external == null) {
                external = new EditableExternal(r.getId(), r.getUrl(), r.getLabel());
            }
        }
        return new CommentRefs(verses, external);
    }

    /**
     * Add verse references to a comment (gated at {@code write}). Duplicates of refs already
     * present are skipped; new refs append after the existing positions with a {@code null}
     * source id — the edition-independent convention the seeded arguments use.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable public id
     * @param theRefs   parsed refs (e.g. from {@code RefListParser}) to add
     * @throws IllegalStateException if not signed in / unknown account / comment not found /
     *                               below {@code write}
     */
    @Transactional
    public void addRefs(final String anEmail, final String aPublicId,
                        final List<org.religioustext.app.service.CommentQueryService.VerseComment.Ref> theRefs) {
        if (theRefs == null || theRefs.isEmpty()) return;
        final Comment comment = editable(anEmail, aPublicId);
        final java.util.Set<String> present = new java.util.HashSet<>();
        int maxPos = -1;
        for (final CommentReference r : comment.getReferences()) {
            maxPos = Math.max(maxPos, r.getPosition());
            if (r.getRefType() == CommentReference.RefType.internal && r.getBookCode() != null)
                present.add(r.getBookCode() + ":" + r.getChapter() + ":" + r.getVerse());
        }
        for (final var ref : theRefs) {
            final String key = ref.bookCode() + ":" + ref.chapter() + ":" + ref.verse();
            if (present.contains(key)) continue;
            present.add(key);
            final CommentReference row = CommentReference.internal(
                comment, ++maxPos, null, ref.bookCode(), ref.chapter(), ref.verse());
            em.persist(row);
            comment.getReferences().add(row);
        }
        comment.setLocallyEdited(true);
    }

    /**
     * Remove one reference (internal or external) from a comment — gated at {@code write}.
     * OrphanRemoval deletes the row on flush. Unknown ids are a no-op.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable public id
     * @param aRefId    the reference row's id
     * @throws IllegalStateException if not signed in / unknown account / comment not found /
     *                               below {@code write}
     */
    @Transactional
    public void removeRef(final String anEmail, final String aPublicId, final String aRefId) {
        final Comment comment = editable(anEmail, aPublicId);
        comment.getReferences().removeIf(r -> r.getId().equals(aRefId));
        comment.setLocallyEdited(true);
    }

    /**
     * Set (or clear) the comment's external link — gated at {@code write}. A blank URL removes
     * every external ref; otherwise the first external ref is updated in place (URL is
     * immutable on the row, so it is replaced) and any duplicates are dropped.
     *
     * @param anEmail   the signed-in user's email
     * @param aPublicId the comment's stable public id
     * @param aUrl      the link URL, or blank to clear
     * @param aLabel    the human label for the link (e.g. the channel name)
     * @throws IllegalStateException if not signed in / unknown account / comment not found /
     *                               below {@code write}
     */
    @Transactional
    public void setExternalLink(final String anEmail, final String aPublicId,
                                final String aUrl, final String aLabel) {
        final Comment comment = editable(anEmail, aPublicId);
        int maxPos = -1;
        for (final CommentReference r : comment.getReferences())
            maxPos = Math.max(maxPos, r.getPosition());
        comment.getReferences().removeIf(r -> r.getRefType() == CommentReference.RefType.external);
        comment.setLocallyEdited(true);
        if (aUrl == null || aUrl.isBlank()) return;
        final CommentReference row = CommentReference.external(
            comment, maxPos + 1, aUrl.strip(),
            aLabel == null || aLabel.isBlank() ? aUrl.strip() : aLabel.strip(), null);
        em.persist(row);
        comment.getReferences().add(row);
    }

    /** Load a comment by public id and require the caller's effective level ≥ write. */
    private Comment editable(final String anEmail, final String aPublicId) {
        final User caller = callerByEmail(anEmail);
        final Comment comment = byPublicId(aPublicId);
        if (!access.canEdit(caller, comment))
            throw new IllegalStateException("Not permitted to edit this comment");
        return comment;
    }

    private User callerByEmail(final String anEmail) {
        if (anEmail == null || anEmail.isBlank()) throw new IllegalStateException("Not signed in");
        return em.createQuery("SELECT u FROM User u WHERE u.email = :email", User.class)
            .setParameter("email", anEmail).setMaxResults(1).getResultList().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("No account for: " + anEmail));
    }

    private Comment byPublicId(final String aPublicId) {
        return em.createQuery("SELECT c FROM Comment c WHERE c.publicId = :p", Comment.class)
            .setParameter("p", aPublicId).setMaxResults(1).getResultList().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Comment not found: " + aPublicId));
    }

    /** Fetch a comment and verify the caller owns it. */
    private Comment owned(final String anEmail, final String aCommentId) {
        final Optional<Comment> found = em.createQuery(
                "SELECT c FROM Comment c WHERE c.id = :id AND c.user.email = :email",
                Comment.class)
            .setParameter("id", aCommentId)
            .setParameter("email", anEmail)
            .setMaxResults(1).getResultList().stream().findFirst();
        return found.orElseThrow(() ->
            new IllegalStateException("Comment not found or not owned by caller"));
    }
}
