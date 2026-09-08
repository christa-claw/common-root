package org.religioustext.app.model.user;

import jakarta.persistence.*;
import org.religioustext.app.util.TypedId;

import java.time.LocalDateTime;

@Entity
@Table(name = "blocked_domains")
public class BlockedDomain {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, unique = true)
    private String domain;

    @Column(length = 500)
    private String reason;

    @Column(name = "blocked_at", nullable = false, updatable = false)
    private LocalDateTime blockedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "blocked_by", nullable = false)
    private User blockedBy;

    @PrePersist
    void onCreate() {
        if (id == null) id = TypedId.generate(TypedId.Type.BLOCKED);
        blockedAt = LocalDateTime.now();
    }

    public String        getId()        { return id; }
    public String        getDomain()    { return domain; }
    public String        getReason()    { return reason; }
    public LocalDateTime getBlockedAt() { return blockedAt; }
    public User          getBlockedBy() { return blockedBy; }

    public void setDomain(final String v)  { this.domain = v; }
    public void setReason(final String v)  { this.reason = v; }
    public void setBlockedBy(final User v) { this.blockedBy = v; }
}
