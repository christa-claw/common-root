package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.ApiUsage;
import org.religioustext.app.repository.ApiUsageRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Quota arithmetic with a pinned clock — the month boundary is a computed fact,
 * never a slept-for one (docs/api-test-cases.md Q1, Q6–Q9).
 */
class ApiQuotaServiceTest {

    private final ApiUsageRepository usage = mock(ApiUsageRepository.class);

    private ApiQuotaService serviceAt(final String anInstant, final long aRequestLimit,
                                      final long aByteLimit, final int aBurst) {
        return new ApiQuotaService(usage,
            Clock.fixed(Instant.parse(anInstant), ZoneOffset.UTC),
            aRequestLimit, aByteLimit, aBurst);
    }

    private void usedRequests(final long aCount) {
        final ApiUsage row = mock(ApiUsage.class);
        when(row.getRequests()).thenReturn(aCount);
        when(usage.findById(any())).thenReturn(Optional.of(row));
    }

    // period / reset arithmetic — the schema-shaping choice, tested as pure functions
    @Test
    void periodIsTheUtcCalendarMonth() {
        assertThat(ApiQuotaService.period(Instant.parse("2026-09-15T12:00:00Z"))).isEqualTo("202609");
        // 01:59 Helsinki on Oct 1 is still 22:59 UTC on Sep 30 — the period is UTC's
        assertThat(ApiQuotaService.period(Instant.parse("2026-09-30T22:59:00Z"))).isEqualTo("202609");
        assertThat(ApiQuotaService.period(Instant.parse("2026-12-31T23:59:59Z"))).isEqualTo("202612");
    }

    @Test
    void resetIsTheFirstOfNextMonthUtc() {
        assertThat(ApiQuotaService.monthResetEpoch(Instant.parse("2026-09-15T12:00:00Z")))
            .isEqualTo(Instant.parse("2026-10-01T00:00:00Z").getEpochSecond());
        // December rolls the year
        assertThat(ApiQuotaService.monthResetEpoch(Instant.parse("2026-12-31T23:59:59Z")))
            .isEqualTo(Instant.parse("2027-01-01T00:00:00Z").getEpochSecond());
    }

    // Q1 — an allowed request increments the month's row
    @Test
    void allowedRequestCountsOnce() {
        when(usage.findById(any())).thenReturn(Optional.empty());
        final ApiQuotaService service = serviceAt("2026-09-15T12:00:00Z", 100, 1000, 60);

        final ApiQuotaService.Decision decision = service.countRequest("key-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.limit()).isEqualTo(100);
        assertThat(decision.remaining()).isEqualTo(99);
        verify(usage).incrementRequests("key-1", "202609");
    }

    // Q6 — over the monthly line: refused, not counted, Retry-After points at the 1st
    @Test
    void overMonthlyQuotaIsRefusedAndNotCounted() {
        usedRequests(100);
        final ApiQuotaService service = serviceAt("2026-09-15T12:00:00Z", 100, 1000, 60);

        final ApiQuotaService.Decision decision = service.countRequest("key-1");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        assertThat(decision.resetEpoch())
            .isEqualTo(Instant.parse("2026-10-01T00:00:00Z").getEpochSecond());
        assertThat(decision.retryAfter())
            .isEqualTo(decision.resetEpoch() - Instant.parse("2026-09-15T12:00:00Z").getEpochSecond());
        verify(usage, never()).incrementRequests(any(), any());
    }

    // Q7 — the month boundary: last second of September vs first of October
    @Test
    void allowanceIsFreshOnTheFirstUtc() {
        usedRequests(100);   // September is exhausted…
        final ApiQuotaService september = serviceAt("2026-09-30T23:59:59Z", 100, 1000, 60);
        assertThat(september.countRequest("key-1").allowed()).isFalse();

        // …but October is a different row, which does not exist yet.
        when(usage.findById(new ApiUsage.Key("key-1", "202610"))).thenReturn(Optional.empty());
        final ApiQuotaService october = serviceAt("2026-10-01T00:00:01Z", 100, 1000, 60);
        assertThat(october.countRequest("key-1").allowed()).isTrue();
        verify(usage).incrementRequests("key-1", "202610");
    }

    // Q8 — burst: request N+1 inside one minute is refused with a sub-minute Retry-After
    @Test
    void burstLimitRefusesInsideTheMinute() {
        when(usage.findById(any())).thenReturn(Optional.empty());
        final ApiQuotaService service = serviceAt("2026-09-15T12:00:30Z", 1_000_000, 1000, 3);

        assertThat(service.countRequest("key-1").allowed()).isTrue();
        assertThat(service.countRequest("key-1").allowed()).isTrue();
        assertThat(service.countRequest("key-1").allowed()).isTrue();
        final ApiQuotaService.Decision fourth = service.countRequest("key-1");
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfter()).isBetween(1L, 60L);
    }

    // Q9 — burst is per key
    @Test
    void burstIsPerKey() {
        when(usage.findById(any())).thenReturn(Optional.empty());
        final ApiQuotaService service = serviceAt("2026-09-15T12:00:30Z", 1_000_000, 1000, 1);

        assertThat(service.countRequest("key-a").allowed()).isTrue();
        assertThat(service.countRequest("key-a").allowed()).isFalse();
        assertThat(service.countRequest("key-b").allowed()).isTrue();
    }

    // Q2/Q3 — bytes meter the byte counter only, and zero bytes count nothing
    @Test
    void bytesCountSeparatelyAndZeroCountsNothing() {
        final ApiQuotaService service = serviceAt("2026-09-15T12:00:00Z", 100, 1000, 60);

        service.countBytes("key-1", 512);
        verify(usage).incrementBytes("key-1", "202609", 512);
        verify(usage, never()).incrementRequests(any(), any());

        service.countBytes("key-1", 0);     // the 304 case
        verify(usage, never()).incrementBytes("key-1", "202609", 0);
    }

    @Test
    void byteAllowancePreflight() {
        final ApiUsage row = mock(ApiUsage.class);
        when(row.getBytes()).thenReturn(900L);
        when(usage.findById(any())).thenReturn(Optional.of(row));
        final ApiQuotaService service = serviceAt("2026-09-15T12:00:00Z", 100, 1000, 60);

        assertThat(service.wouldExceedBytes("key-1", 100).allowed()).isTrue();
        assertThat(service.wouldExceedBytes("key-1", 101).allowed()).isFalse();
    }
}
