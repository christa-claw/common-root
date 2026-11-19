// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.service.ApiKeyService.CreatedKey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link AppSignInService}: the PKCE check, one use per code, expiry, and the redirect allow-list. */
class AppSignInServiceTest {

    // RFC 7636 appendix B
    private static final String VERIFIER  = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    private static final String APP       = "commonroot://auth";

    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final MovableClock  clock = new MovableClock(Instant.parse("2026-11-19T12:00:00Z"));
    private final AppSignInService service = new AppSignInService(keys, clock, " http://localhost:8082/auth ");

    AppSignInServiceTest() {
        when(keys.createForApp("usr-1")).thenReturn(new CreatedKey("crk_" + "b".repeat(40), new ApiKey()));
    }

    @Test
    void s256MatchesTheRfcExample() {
        assertThat(AppSignInService.s256(VERIFIER)).isEqualTo(CHALLENGE);
    }

    @Test
    void aCodeAndItsVerifierBuyAKey() {
        final String code = service.issueCode("usr-1", CHALLENGE, APP);
        assertThat(service.redeem(code, VERIFIER, APP)).isPresent();
        verify(keys).createForApp("usr-1");
    }

    @Test
    void aCodeWorksOnce() {
        final String code = service.issueCode("usr-1", CHALLENGE, APP);
        assertThat(service.redeem(code, VERIFIER, APP)).isPresent();
        assertThat(service.redeem(code, VERIFIER, APP)).isEmpty();
    }

    @Test
    void aWrongVerifierSpendsTheCode() {
        final String code = service.issueCode("usr-1", CHALLENGE, APP);
        assertThat(service.redeem(code, "x".repeat(43), APP)).isEmpty();
        assertThat(service.redeem(code, VERIFIER, APP)).isEmpty();
        verify(keys, never()).createForApp("usr-1");
    }

    @Test
    void theRedirectMustBeTheOneTheCodeWasIssuedFor() {
        final String code = service.issueCode("usr-1", CHALLENGE, APP);
        assertThat(service.redeem(code, VERIFIER, "commonroot-test://auth")).isEmpty();
    }

    @Test
    void codesExpire() {
        final String code = service.issueCode("usr-1", CHALLENGE, APP);
        clock.advance(AppSignInService.CODE_LIFETIME.plusSeconds(1));
        assertThat(service.redeem(code, VERIFIER, APP)).isEmpty();
    }

    @Test
    void onlyTheAppsAddressesAndConfiguredDevOnesAreAllowed() {
        assertThat(service.isAllowedRedirect("commonroot://auth")).isTrue();
        assertThat(service.isAllowedRedirect("commonroot-test://auth")).isTrue();
        assertThat(service.isAllowedRedirect("http://localhost:8082/auth")).isTrue();
        assertThat(service.isAllowedRedirect("commonroot://auth/evil")).isFalse();
        assertThat(service.isAllowedRedirect("https://evil.example.com/auth")).isFalse();
        assertThat(new AppSignInService(keys, clock, "").isAllowedRedirect("http://localhost:8082/auth")).isFalse();
        assertThatThrownBy(() -> service.issueCode("usr-1", CHALLENGE, "https://evil.example.com/"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChallengeHasTheShapeOfAnS256() {
        assertThat(AppSignInService.isChallenge(CHALLENGE)).isTrue();
        assertThat(AppSignInService.isChallenge("plain")).isFalse();
        assertThat(AppSignInService.isChallenge(CHALLENGE + "=")).isFalse();
        assertThat(AppSignInService.isChallenge(null)).isFalse();
    }

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(final Instant aStart) { this.now = aStart; }
        void advance(final Duration aDuration) { now = now.plus(aDuration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(final ZoneId aZone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
