// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "comments")
public class Comment {

    public enum ModerationStatus { approved, pending, rejected }

    @Id
    @Column(length = 40)
    private String id;

    /** Stable public permalink id (?comment=cmt_…). For seeded arguments this is
     *  the deterministic cmt_&lt;uuid5&gt; from arguments.json — stable across the
     *  delete-and-reseed cycle that re-mints the row id at every startup. For
     *  user-authored comments it defaults to the row id (never reseeded, so the
     *  row id is already stable). Immutable after creation. */
    @Column(name = "public_id", length = 40, unique = true, updatable = false)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.approved;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Optional CUSTOM (instance) ACL for THIS comment (V11). NULL = inherit the org default
    // → system default. Set only via a comment-context permission edit (copy-on-write).
    // See docs/access-control.md §4a.
    @Column(name = "acl_id", length = 40)
    private String aclId;

    // TRUE once a human edited this row through the editor (V14). The startup reseed
    // PRESERVES locally-edited seeded comments verbatim instead of rebuilding them from
    // the ledger — a channel's correction must survive every restart.
    @Column(name = "locally_edited", nullable = false)
    private boolean locallyEdited = false;

    // Which seed ledger this row belongs to (V16): 'arguments' for
    // transcripts/arguments.json rows, 'notes-<edition>' for a translator-note
    // ledger (e.g. 'notes-lut1912'), NULL for user-authored comments. Each
    // seeder's merge + orphan sweep is scoped to ITS OWN ledger by this column,
    // so two ledgers can never delete each other's rows. Additionally, rows of
    // a 'notes-%' ledger are edition-BOUND on the read side (they render only
    // under their own edition's column), unlike every other comment, whose
    // sourceId is provenance only (the V5 edition-independence rule).
    @Column(name = "seed_ledger", length = 40, updatable = false)
    private String seedLedger;

    @OneToMany(mappedBy = "comment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<CommentReference> references = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.COMMENT);
        if (publicId == null) publicId = id;   // user-authored path: row id is stable
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    /**
     * Makes this comment public.
     * If it has external references → pending (needs admin review).
     * If no external references → approved immediately.
     */
    public void makePublic() {
        this.isPublic = true;
        final boolean hasExternalRefs = references.stream()
            .anyMatch(r -> r.getRefType() == CommentReference.RefType.external);
        this.moderationStatus = hasExternalRefs
            ? ModerationStatus.pending
            : ModerationStatus.approved;
        this.rejectionReason = null;
    }

    /** Retracts a public comment — removes from public view immediately. */
    public void makePrivate() {
        this.isPublic = false;
        this.moderationStatus = ModerationStatus.approved;
        this.rejectionReason = null;
    }

    /** Admin approves a pending comment. */
    public void approve() {
        this.moderationStatus = ModerationStatus.approved;
        this.rejectionReason = null;
    }

    /** Admin rejects a pending comment with a reason. */
    public void reject(final String aReason) {
        this.moderationStatus = ModerationStatus.rejected;
        this.rejectionReason = aReason;
        this.isPublic = false;
    }

    /** Whether this comment is visible to the general public. */
    public boolean isVisiblePublicly() {
        return isPublic && moderationStatus == ModerationStatus.approved;
    }

    public String               getId()               { return id; }
    public String               getPublicId()         { return publicId; }
    public User                 getUser()             { return user; }
    public String               getContent()          { return content; }
    public boolean              isPublic()            { return isPublic; }
    public ModerationStatus     getModerationStatus() { return moderationStatus; }
    public String               getRejectionReason()  { return rejectionReason; }
    public LocalDateTime        getCreatedAt()        { return createdAt; }
    public LocalDateTime        getUpdatedAt()        { return updatedAt; }
    public List<CommentReference> getReferences()     { return references; }
    public String               getAclId()            { return aclId; }
    public boolean              isLocallyEdited()     { return locallyEdited; }
    public String               getSeedLedger()       { return seedLedger; }

    public void setLocallyEdited(final boolean aLocallyEdited) { this.locallyEdited = aLocallyEdited; }
    public void setUser(final User aUser)      { this.user = aUser; }
    public void setContent(final String aContent) { this.content = aContent; }
    public void setAclId(final String anAclId)   { this.aclId = anAclId; }
    /** Only meaningful BEFORE the first persist (the column is insert-only). */
    public void setPublicId(final String aPublicId){ this.publicId = aPublicId; }
    /** Only meaningful BEFORE the first persist (the column is insert-only). */
    public void setSeedLedger(final String aSeedLedger) { this.seedLedger = aSeedLedger; }
}
