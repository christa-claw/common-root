// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * One API key's usage in one calendar month, UTC (V18; docs/api-spec.md §5).
 *
 * <p>Two counters, never one clever unit: {@link #requests} meters API calls,
 * {@link #bytes} meters download volume. A bulk download is one request and
 * many megabytes; a verse lookup is one request and two kilobytes — a single
 * combined counter would make one of them effectively free.
 *
 * <p>The period is {@code yyyymm} in UTC — one row per key per month, so
 * "resets on the 1st" is both the implementation and the documentation.
 * Increments go through the atomic upsert in {@code ApiUsageRepository}, not
 * through this entity's setters; the entity exists for reads.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Entity
@Table(name = "api_usage")
@IdClass(ApiUsage.Key.class)
public class ApiUsage {

    @Id
    @Column(name = "api_key_id", length = 40)
    private String apiKeyId;

    /** Calendar month, {@code yyyymm}, UTC. */
    @Id
    @Column(length = 6)
    private String period;

    @Column(nullable = false)
    private long requests;

    @Column(nullable = false)
    private long bytes;

    public String getApiKeyId() { return apiKeyId; }
    public String getPeriod()   { return period; }
    public long   getRequests() { return requests; }
    public long   getBytes()    { return bytes; }

    /** Composite id: (key, month). */
    public static class Key implements Serializable {
        private String apiKeyId;
        private String period;

        public Key() { }

        public Key(final String anApiKeyId, final String aPeriod) {
            this.apiKeyId = anApiKeyId;
            this.period   = aPeriod;
        }

        @Override public boolean equals(final Object anOther) {
            if (this == anOther) return true;
            if (!(anOther instanceof Key other)) return false;
            return Objects.equals(apiKeyId, other.apiKeyId)
                && Objects.equals(period, other.period);
        }

        @Override public int hashCode() { return Objects.hash(apiKeyId, period); }
    }
}
