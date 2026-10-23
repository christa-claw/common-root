// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class LoginAnalyticsTest {

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void aFormLoginMarksTheSessionOfTheRequestThatAuthenticated() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new LoginAnalytics().onLogin(new InteractiveAuthenticationSuccessEvent(
            new UsernamePasswordAuthenticationToken("someone", "x"), getClass()));

        assertThat(request.getSession().getAttribute(LoginAnalytics.FLAG)).isEqualTo(Boolean.TRUE);
    }

    @Test
    void theMarkCarriesNothingAboutThePerson() {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        new LoginAnalytics().onLogin(new InteractiveAuthenticationSuccessEvent(
            new UsernamePasswordAuthenticationToken("someone@example.org", "x"), getClass()));

        assertThat(java.util.Collections.list(request.getSession().getAttributeNames()))
            .containsExactly(LoginAnalytics.FLAG);
        assertThat(String.valueOf(request.getSession().getAttribute(LoginAnalytics.FLAG)))
            .doesNotContain("someone");
    }

    @Test
    void anEventWithNoRequestInFlightIsIgnoredRatherThanFailing() {
        RequestContextHolder.resetRequestAttributes();

        new LoginAnalytics().onLogin(new InteractiveAuthenticationSuccessEvent(
            new UsernamePasswordAuthenticationToken("someone", "x"), getClass()));
        // no exception is the assertion
    }
}
