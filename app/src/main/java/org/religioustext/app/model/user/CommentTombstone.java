package org.religioustext.app.model.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * A deletion marker for a SEEDED (system-account) comment. The startup reseed rebuilds
 * channel comments from {@code arguments.json}; without a tombstone, a comment a channel
 * deliberately deleted through the editor would simply resurrect on the next boot. The
 * reseed skips any ledger entry whose {@code public_id} carries a tombstone (V14).
 *
 * @author Christa Claw
 * @version 0.3.10
 * @since 0.3.10
 */
@Entity
@Table(name = "comment_tombstones")
public class CommentTombstone {

    /** The deleted comment's stable {@code cmt_…} permalink id. */
    @Id
    @Column(name = "public_id", length = 40)
    private String publicId;

    @Column(name = "deleted_at", nullable = false)
    private LocalDateTime deletedAt;

    protected CommentTombstone() { }

    public CommentTombstone(final String aPublicId) {
        this.publicId = aPublicId;
        this.deletedAt = LocalDateTime.now();
    }

    public String        getPublicId()  { return publicId; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
}
