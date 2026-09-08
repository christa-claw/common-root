package org.religioustext.app.repository;

import org.religioustext.app.model.user.ApiUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@link ApiUsage} counter rows, plus the atomic upsert
 * increments the quota service uses. The increments are native
 * {@code INSERT … ON DUPLICATE KEY UPDATE} statements so two concurrent requests
 * on the same key can never lose an update — read-modify-write through the
 * entity would.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
public interface ApiUsageRepository extends JpaRepository<ApiUsage, ApiUsage.Key> {

    /**
     * Count one request against a key's month, creating the row if this is the
     * month's first.
     *
     * @param aKeyId  the {@code key-} id of the authenticated key
     * @param aPeriod the calendar month, {@code yyyymm} UTC
     */
    @Modifying
    @Query(value = "INSERT INTO api_usage (api_key_id, period, requests, bytes) "
                 + "VALUES (:keyId, :period, 1, 0) "
                 + "ON DUPLICATE KEY UPDATE requests = requests + 1",
           nativeQuery = true)
    void incrementRequests(@Param("keyId") final String aKeyId,
                           @Param("period") final String aPeriod);

    /**
     * Count served download bytes against a key's month, creating the row if this
     * is the month's first. Requests are deliberately not incremented here — a
     * download consumes the byte counter, not the request counter (two counters,
     * never one clever unit).
     *
     * @param aKeyId  the {@code key-} id of the authenticated key
     * @param aPeriod the calendar month, {@code yyyymm} UTC
     * @param aBytes  bytes actually served (a 304 serves none and counts zero)
     */
    @Modifying
    @Query(value = "INSERT INTO api_usage (api_key_id, period, requests, bytes) "
                 + "VALUES (:keyId, :period, 0, :bytes) "
                 + "ON DUPLICATE KEY UPDATE bytes = bytes + :bytes",
           nativeQuery = true)
    void incrementBytes(@Param("keyId") final String aKeyId,
                        @Param("period") final String aPeriod,
                        @Param("bytes") final long aBytes);
}
