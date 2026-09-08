// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

@Entity
@Table(name = "comment_references")
public class CommentReference {

    public enum RefType { internal, external }

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comment_id", nullable = false)
    private Comment comment;

    @Column(nullable = false)
    private int position = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false)
    private RefType refType;

    // Internal reference fields
    @Column(name = "source_id", length = 100)
    private String sourceId;

    @Column(name = "book_code", length = 10)
    private String bookCode;

    private Integer chapter;
    private Integer verse;

    // Timecoded video link for an internal (verse) reference — "play the video
    // at the point where this verse is discussed". Null for ordinary refs.
    @Column(name = "video_url", length = 2048)
    private String videoUrl;

    // External reference fields
    @Column(length = 2048)
    private String url;

    @Column(length = 255)
    private String label;

    @Column(length = 1000)
    private String description;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.COMMENT_REF);
    }

    /** Factory — internal verse reference. */
    public static CommentReference internal(final Comment aComment, final int aPosition,
                                             final String aSourceId, final String aBookCode,
                                             final int aChapter, final int aVerse) {
        final CommentReference ref = new CommentReference();
        ref.comment  = aComment;
        ref.position = aPosition;
        ref.refType  = RefType.internal;
        ref.sourceId = aSourceId;
        ref.bookCode = aBookCode;
        ref.chapter  = aChapter;
        ref.verse    = aVerse;
        return ref;
    }

    /** Factory — external URL reference. */
    public static CommentReference external(final Comment aComment, final int aPosition,
                                             final String aUrl, final String aLabel,
                                             final String aDescription) {
        final CommentReference ref = new CommentReference();
        ref.comment     = aComment;
        ref.position    = aPosition;
        ref.refType     = RefType.external;
        ref.url         = aUrl;
        ref.label       = aLabel;
        ref.description = aDescription;
        return ref;
    }

    public String  getId()          { return id; }
    public Comment getComment()     { return comment; }
    public int     getPosition()    { return position; }
    public RefType getRefType()     { return refType; }
    public String  getSourceId()    { return sourceId; }
    public String  getBookCode()    { return bookCode; }
    public Integer getChapter()     { return chapter; }
    public Integer getVerse()       { return verse; }
    public String  getUrl()         { return url; }
    public String  getLabel()       { return label; }
    public String  getDescription() { return description; }
    public String  getVideoUrl()    { return videoUrl; }

    public void setPosition(final int aPosition)       { this.position = aPosition; }
    public void setDescription(final String aDescription) { this.description = aDescription; }
    public void setVideoUrl(final String aVideoUrl)    { this.videoUrl = aVideoUrl; }
}
