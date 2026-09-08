package org.religioustext.app.model.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Per-user reader preferences: one row per user, created on first save.
 *
 * String-valued fields hold ReaderLink TOKENS (src / mode / order), not enum
 * names or database ids — the same vocabulary shareable links use, so applying
 * preferences is synthesizing a link and the row stays human-readable.
 * lastPosition IS a reader link's query string (what Copy-link emits, minus
 * "/reader?"), written while resumeEnabled is on and replayed when the reader
 * is opened without any link state.
 */
@Entity
@Table(name = "user_preferences")
public class UserPreferences {

    /** The owning user's id (users.id) — a strict 1:1, so it is the PK. */
    @Id
    @Column(name = "user_id", length = 40)
    private String userId;

    @Column(name = "default_source", length = 40)
    private String defaultSource;

    @Column(name = "default_mode", length = 20)
    private String defaultMode;

    @Column(name = "default_order", length = 20)
    private String defaultOrder;

    @Column(name = "show_comments_panel", nullable = false)
    private boolean showCommentsPanel;

    @Column(name = "show_comment_markers", nullable = false)
    private boolean showCommentMarkers;

    /** Newline-separated voices this reader has muted (V17) — channel names and
     *  author display names in one list. Null/blank = mute nothing. */
    @Column(name = "muted_voices", columnDefinition = "TEXT")
    private String mutedVoices;

    @Column(name = "resume_enabled", nullable = false)
    private boolean resumeEnabled;

    @Column(name = "last_position", length = 2000)
    private String lastPosition;

    @Column(name = "ui_language", length = 10)
    private String uiLanguage;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() { updatedAt = LocalDateTime.now(); }

    public String  getUserId()             { return userId; }
    public String  getDefaultSource()      { return defaultSource; }
    public String  getDefaultMode()        { return defaultMode; }
    public String  getDefaultOrder()       { return defaultOrder; }
    public boolean isShowCommentsPanel()   { return showCommentsPanel; }
    public boolean isShowCommentMarkers()  { return showCommentMarkers; }
    public String  getMutedVoices()         { return mutedVoices; }
    public boolean isResumeEnabled()       { return resumeEnabled; }
    public String  getLastPosition()       { return lastPosition; }
    public String  getUiLanguage()         { return uiLanguage; }
    public LocalDateTime getUpdatedAt()    { return updatedAt; }

    public void setUserId(final String aUserId)             { this.userId = aUserId; }
    public void setDefaultSource(final String aDefaultSource)      { this.defaultSource = aDefaultSource; }
    public void setDefaultMode(final String aDefaultMode)        { this.defaultMode = aDefaultMode; }
    public void setDefaultOrder(final String aDefaultOrder)       { this.defaultOrder = aDefaultOrder; }
    public void setShowCommentsPanel(final boolean aShowCommentsPanel) { this.showCommentsPanel = aShowCommentsPanel; }
    public void setShowCommentMarkers(final boolean aShowCommentMarkers){ this.showCommentMarkers = aShowCommentMarkers; }
    public void setMutedVoices(final String aMutedVoices)          { this.mutedVoices = aMutedVoices; }
    public void setResumeEnabled(final boolean aResumeEnabled)     { this.resumeEnabled = aResumeEnabled; }
    public void setLastPosition(final String aLastPosition)       { this.lastPosition = aLastPosition; }
    public void setUiLanguage(final String aUiLanguage)         { this.uiLanguage = aUiLanguage; }
}
