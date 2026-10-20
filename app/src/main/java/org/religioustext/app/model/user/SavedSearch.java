// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

/**
 * A full-text search a user chose to keep. Search takes only a query, so the
 * query text is the whole of it. One row per (user, query); private to its owner.
 *
 * @author Christa Claw
 * @version 0.11.3
 * @since 0.11.3
 */
@Entity
@Table(name = "saved_searches",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_saved_search", columnNames = {"user_id", "query_text"}))
public class SavedSearch {

    @Id
    @Column(length = 40)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "query_text", nullable = false, length = 200)
    private String queryText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.SAVED_SEARCH);
        createdAt = LocalDateTime.now();
    }

    public String        getId()        { return id; }
    public User          getUser()      { return user; }
    public String        getQueryText() { return queryText; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setUser(final User aUser)            { this.user = aUser; }
    public void setQueryText(final String aQueryText) { this.queryText = aQueryText; }
}
