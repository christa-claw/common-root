// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The phone app's daily allowance of licensed reads: counted per key, renewed
 * at midnight UTC, and never spent by anyone but the app.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
class AppReadPolicyTest {

    private static final SourceRow NIV = SourceRow.of(new String[] {
        "bible-niv", "New International Version", "NIV", "ltr", "Licensed", "", "bible", "en" });
    private static final SourceRow KJV = SourceRow.of(new String[] {
        "bible-kjv-1611", "King James Version", "KJV", "ltr", "Public Domain", "", "bible", "en" });

    /** A clock whose moment the test moves. */
    private static final class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-19T23:59:00Z"));
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(final java.time.ZoneId aZone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }

    private static MockHttpServletRequest request(final String aKeyId, final boolean theAppFlag) {
        final ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn(aKeyId);
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ApiAuthFilter.ATTR_KEY, new ResolvedKey(key, null));
        if (theAppFlag) req.addHeader(AppReadPolicy.CLIENT_HEADER, AppReadPolicy.APP_CLIENT);
        return req;
    }

    @Test
    void publicDomainIsOpenToAnyoneAndCountsNothing() {
        final AppReadPolicy policy = new AppReadPolicy(new MovableClock(), 1);
        for (int i = 0; i < 5; i++) {
            assertThat(policy.access(KJV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.OPEN);
        }
        assertThat(policy.access(NIV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.APP);
    }

    @Test
    void licensedTextIsTheAppsAloneAndRefusingCountsNothing() {
        final AppReadPolicy policy = new AppReadPolicy(new MovableClock(), 1);
        assertThat(policy.access(NIV, request("key-1", false))).isEqualTo(AppReadPolicy.Access.REFUSED);
        assertThat(policy.access(NIV, null)).isEqualTo(AppReadPolicy.Access.REFUSED);
        assertThat(policy.access(NIV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.APP);
    }

    @Test
    void eachKeyHasItsOwnAllowanceWhichRenewsAtMidnightUtc() {
        final MovableClock clock = new MovableClock();
        final AppReadPolicy policy = new AppReadPolicy(clock, 2);
        assertThat(policy.access(NIV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.APP);
        assertThat(policy.access(NIV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.APP);
        assertThat(policy.access(NIV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.SPENT);
        assertThat(policy.access(NIV, request("key-2", true))).isEqualTo(AppReadPolicy.Access.APP);

        final ResponseEntity<Object> spent = policy.refusal(NIV, AppReadPolicy.Access.SPENT);
        assertThat(spent.getStatusCode().value()).isEqualTo(429);
        assertThat(spent.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(((ApiError) spent.getBody()).error()).isEqualTo("daily_reading_limit");

        clock.now.set(Instant.parse("2026-11-20T00:00:01Z"));
        assertThat(policy.access(NIV, request("key-1", true))).isEqualTo(AppReadPolicy.Access.APP);
    }
}
