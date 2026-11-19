// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.service.ApiKeyService;
import org.religioustext.app.service.ApiKeyService.CreatedKey;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link ApiGuestKeyController}: issuing guest keys, the two hourly limits, and whose address counts. */
class ApiGuestKeyControllerTest {

    private final ApiKeyService keys = mock(ApiKeyService.class);
    private final Clock         clock = Clock.fixed(Instant.parse("2026-11-18T10:15:00Z"), ZoneOffset.UTC);

    ApiGuestKeyControllerTest() {
        final ApiKey row = new ApiKey();
        row.setKeyId("a81f02xx");
        when(keys.createGuest()).thenReturn(new CreatedKey("crk_" + "a".repeat(40), row));
    }

    private static MockHttpServletRequest from(final String aRemote, final String aForwarded) {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/guest-keys");
        request.setRemoteAddr(aRemote);
        if (aForwarded != null) request.addHeader("X-Forwarded-For", aForwarded);
        return request;
    }

    @Test
    void issuesAReadOnlyKey() {
        final ApiGuestKeyController controller = new ApiGuestKeyController(keys, clock, 5, 300);
        final ResponseEntity<Object> response = controller.issue(from("203.0.113.9", null));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("key", "crk_" + "a".repeat(40))
                        .containsEntry("keyId", "a81f02xx")
                        .containsEntry("readOnly", true);
    }

    @Test
    void oneAddressIsLimitedPerHour() {
        final ApiGuestKeyController controller = new ApiGuestKeyController(keys, clock, 2, 300);
        assertThat(controller.issue(from("203.0.113.9", null)).getStatusCode().value()).isEqualTo(201);
        assertThat(controller.issue(from("203.0.113.9", null)).getStatusCode().value()).isEqualTo(201);

        final ResponseEntity<Object> third = controller.issue(from("203.0.113.9", null));
        assertThat(third.getStatusCode().value()).isEqualTo(429);
        assertThat(third.getHeaders().getFirst("Retry-After")).isEqualTo("2700");   // 45 minutes to the hour

        // another address still gets one
        assertThat(controller.issue(from("198.51.100.4", null)).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void everyoneTogetherIsLimitedPerHour() {
        final ApiGuestKeyController controller = new ApiGuestKeyController(keys, clock, 5, 2);
        controller.issue(from("203.0.113.1", null));
        controller.issue(from("203.0.113.2", null));
        assertThat(controller.issue(from("203.0.113.3", null)).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void theProxysLastForwardedEntryIsTheReader() {
        // through Caddy: the last hop is the one Caddy appended
        assertThat(ApiGuestKeyController.clientAddress(from("172.18.0.5", "10.9.9.9, 203.0.113.9")))
            .isEqualTo("203.0.113.9");
        // straight from outside: a forged header buys nothing
        assertThat(ApiGuestKeyController.clientAddress(from("203.0.113.9", "198.51.100.1")))
            .isEqualTo("203.0.113.9");
        assertThat(ApiGuestKeyController.clientAddress(from("127.0.0.1", null))).isEqualTo("127.0.0.1");
    }

    @Test
    void internalAddresses() {
        assertThat(ApiGuestKeyController.isInternal("172.16.0.1")).isTrue();
        assertThat(ApiGuestKeyController.isInternal("172.31.255.1")).isTrue();
        assertThat(ApiGuestKeyController.isInternal("172.32.0.1")).isFalse();
        assertThat(ApiGuestKeyController.isInternal("192.168.1.78")).isTrue();
        assertThat(ApiGuestKeyController.isInternal("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(ApiGuestKeyController.isInternal("8.8.8.8")).isFalse();
    }
}
