package org.religioustext.app.service;

import org.religioustext.app.model.user.ApiUsage;
import org.religioustext.app.repository.ApiUsageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Meters API usage per key (docs/api-spec.md §5): two monthly counters —
 * requests and download bytes — plus a per-key burst limit.
 *
 * <p><b>Calendar month, UTC.</b> One counter row per key per month
 * ({@code yyyymm}); the allowance resets on the 1st, 00:00 UTC, and that
 * sentence is the whole model. The clock is injected so the month boundary is
 * testable without waiting for one.
 *
 * <p><b>Quota principle:</b> never block the honest use case, only the
 * pathological one. The defaults (bindable in application.properties) are sized
 * so a researcher pulling the whole public-domain corpus never notices the
 * meter; abuse should look like 100×, not 2×.
 *
 * <p><b>Burst</b> is a per-key in-memory counter over the current minute — one
 * box, one JVM, so no distributed state. It protects the machine from
 * tight-loop clients regardless of monthly headroom; it empties on restart,
 * which is fine, because its job is smoothing, not accounting.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Service
public class ApiQuotaService {

    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

    private final ApiUsageRepository usage;
    private final Clock              clock;

    private final long requestsPerMonth;
    private final long bytesPerMonth;
    private final int  burstPerMinute;

    /** Per-key request counts for the current minute: keyRowId -> (minuteStamp, count). */
    private final Map<String, MinuteWindow> burst = new ConcurrentHashMap<>();

    public ApiQuotaService(
             final ApiUsageRepository anApiUsageRepository
            , final Clock aClock
            , @Value("${api.quota.requests-per-month:20000}") final long aRequestsPerMonth
            , @Value("${api.quota.bytes-per-month:5368709120}") final long aBytesPerMonth
            , @Value("${api.quota.burst-per-minute:60}") final int aBurstPerMinute) {
        this.usage            = anApiUsageRepository;
        this.clock            = aClock;
        this.requestsPerMonth = aRequestsPerMonth;
        this.bytesPerMonth    = aBytesPerMonth;
        this.burstPerMinute   = aBurstPerMinute;
    }

    /**
     * The outcome of a quota check, with everything a response needs to say:
     * the rate-limit headers on success, {@code Retry-After} on refusal.
     *
     * @param allowed      whether the request may proceed
     * @param limit        the allowance of the counter consulted
     * @param remaining    what is left of it after this request (0 when refused)
     * @param resetEpoch   when the counter resets, epoch seconds
     * @param retryAfter   seconds to wait, only meaningful when refused
     */
    public record Decision(boolean allowed, long limit, long remaining,
                           long resetEpoch, long retryAfter) { }

    /**
     * Meter one API request against a key: burst first (cheap, in-memory), then
     * the monthly request counter. Counting happens only when the request is
     * allowed — a refused request must not consume allowance.
     *
     * @param aKeyRowId the authenticated key's {@code key-} row id
     * @return the decision, carrying header values either way
     */
    @Transactional
    public Decision countRequest(final String aKeyRowId) {
        final Instant now = clock.instant();

        // Burst window first: refusing here is free and needs no DB.
        final MinuteWindow window = burst.compute(aKeyRowId, (k, w) -> {
            final long minute = now.getEpochSecond() / 60;
            if (w == null || w.minute != minute) return new MinuteWindow(minute, 1);
            return new MinuteWindow(minute, w.count + 1);
        });
        if (window.count > burstPerMinute) {
            final long nextMinute = (window.minute + 1) * 60;
            return new Decision(false, burstPerMinute, 0, nextMinute,
                                Math.max(1, nextMinute - now.getEpochSecond()));
        }

        final String period = period(now);
        final long used = currentRequests(aKeyRowId, period);
        if (used >= requestsPerMonth) {
            final long reset = monthResetEpoch(now);
            return new Decision(false, requestsPerMonth, 0, reset,
                                Math.max(1, reset - now.getEpochSecond()));
        }
        usage.incrementRequests(aKeyRowId, period);
        return new Decision(true, requestsPerMonth, requestsPerMonth - used - 1,
                            monthResetEpoch(now), 0);
    }

    /**
     * Meter served download bytes against a key. Called with the byte count
     * actually sent — a 304 serves nothing and must not be counted at all.
     * The pre-flight check for "would this download exceed the allowance" is
     * {@link #wouldExceedBytes}.
     *
     * @param aKeyRowId the authenticated key's {@code key-} row id
     * @param aBytes    bytes actually served
     */
    @Transactional
    public void countBytes(final String aKeyRowId, final long aBytes) {
        if (aBytes <= 0) return;
        usage.incrementBytes(aKeyRowId, period(clock.instant()), aBytes);
    }

    /**
     * Whether serving a download of this size would push the key past its
     * monthly byte allowance.
     *
     * @param aKeyRowId the authenticated key's {@code key-} row id
     * @param aBytes    the download's size
     * @return the decision, carrying byte-counter header values
     */
    public Decision wouldExceedBytes(final String aKeyRowId, final long aBytes) {
        final Instant now = clock.instant();
        final long used = currentBytes(aKeyRowId, period(now));
        final long reset = monthResetEpoch(now);
        if (used + aBytes > bytesPerMonth) {
            return new Decision(false, bytesPerMonth, Math.max(0, bytesPerMonth - used),
                                reset, Math.max(1, reset - now.getEpochSecond()));
        }
        return new Decision(true, bytesPerMonth, bytesPerMonth - used - aBytes, reset, 0);
    }

    /**
     * The calendar month a moment falls in, UTC.
     *
     * @param aMoment the moment
     * @return {@code yyyymm}
     */
    static String period(final Instant aMoment) {
        return PERIOD.format(aMoment.atZone(ZoneOffset.UTC));
    }

    /**
     * When the month containing a moment ends: the 1st of the next month,
     * 00:00 UTC, as epoch seconds.
     *
     * @param aMoment the moment
     * @return the reset instant, epoch seconds
     */
    static long monthResetEpoch(final Instant aMoment) {
        final ZonedDateTime start = aMoment.atZone(ZoneOffset.UTC)
            .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC);
        return start.plusMonths(1).toEpochSecond();
    }

    private long currentRequests(final String aKeyRowId, final String aPeriod) {
        return usage.findById(new ApiUsage.Key(aKeyRowId, aPeriod))
                    .map(ApiUsage::getRequests).orElse(0L);
    }

    private long currentBytes(final String aKeyRowId, final String aPeriod) {
        return usage.findById(new ApiUsage.Key(aKeyRowId, aPeriod))
                    .map(ApiUsage::getBytes).orElse(0L);
    }

    /** One key's request count within one wall-clock minute. */
    private record MinuteWindow(long minute, int count) { }
}
