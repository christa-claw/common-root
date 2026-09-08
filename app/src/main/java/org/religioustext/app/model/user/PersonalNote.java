package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

/**
 * A private, per-verse study note belonging to one user.
 *
 * EDITION-INDEPENDENT (see V6): one note per (user, book, chapter, verse) —
 * a note on John 3:16 belongs to the verse and shows in every edition. sourceId
 * is optional provenance (the edition open when the note was written), not part
 * of the note's identity. Private to the author at all times.
 */
@Entity
@Table(name = "personal_notes",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_note_per_verse",
        columnNames = {"user_id", "book_code", "chapter", "verse"}))
public class PersonalNote {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Optional provenance: which edition was open when the note was written.
     *  Not part of identity — a note shows on its verse in every edition. */
    @Column(name = "source_id", length = 100)
    private String sourceId;

    @Column(name = "book_code", nullable = false, length = 10)
    private String bookCode;

    @Column(nullable = false)
    private int chapter;

    @Column(nullable = false)
    private int verse;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.NOTE);
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public String        getId()        { return id; }
    public User          getUser()      { return user; }
    public String        getSourceId()  { return sourceId; }
    public String        getBookCode()  { return bookCode; }
    public int           getChapter()   { return chapter; }
    public int           getVerse()     { return verse; }
    public String        getContent()   { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setUser(final User aUser)       { this.user = aUser; }
    public void setSourceId(final String aSourceId) { this.sourceId = aSourceId; }
    public void setBookCode(final String aBookCode) { this.bookCode = aBookCode; }
    public void setChapter(final int aChapter)     { this.chapter = aChapter; }
    public void setVerse(final int aVerse)       { this.verse = aVerse; }
    public void setContent(final String aContent)  { this.content = aContent; }
}
