// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

/**
 * A private verse highlight belonging to one user: one colour per (user, book, chapter, verse).
 *
 * EDITION-INDEPENDENT, like {@link PersonalNote} (V6): a highlight on John 3:16 belongs to the
 * verse and shows in every edition. Private to its owner at all times.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
@Entity
@Table(name = "highlights",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_highlight_per_verse",
        columnNames = {"user_id", "book_code", "chapter", "verse"}))
public class Highlight {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "book_code", nullable = false, length = 10)
    private String bookCode;

    @Column(nullable = false)
    private int chapter;

    @Column(nullable = false)
    private int verse;

    /** A key of {@code HighlightColours}. */
    @Column(nullable = false, length = 16)
    private String colour;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.HIGHLIGHT);
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public String getId()       { return id; }
    public User   getUser()     { return user; }
    public String getBookCode() { return bookCode; }
    public int    getChapter()  { return chapter; }
    public int    getVerse()    { return verse; }
    public String getColour()   { return colour; }

    public void setUser(final User aUser)           { this.user = aUser; }
    public void setBookCode(final String aBookCode) { this.bookCode = aBookCode; }
    public void setChapter(final int aChapter)      { this.chapter = aChapter; }
    public void setVerse(final int aVerse)          { this.verse = aVerse; }
    public void setColour(final String aColour)     { this.colour = aColour; }
}
