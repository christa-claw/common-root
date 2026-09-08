package org.religioustext.app.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.model.user.User;
import org.religioustext.app.service.ApiKeyService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.ApiQuotaService;
import org.religioustext.app.service.ApiQuotaService.Decision;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The gate on /api/**: header-only auth, the health exemption, quota headers on
 * every metered response, and no quota consumption by failed auth
 * (docs/api-test-cases.md K4–K8, Q4, Q10, H5).
 */
class ApiAuthFilterTest {

    private static final String VALID = "crk_" + "a".repeat(40);

    private final ApiKeyService   keys   = mock(ApiKeyService.class);
    private final ApiQuotaService quota  = mock(ApiQuotaService.class);
    private final ApiAuthFilter   filter = new ApiAuthFilter(keys, quota, new ObjectMapper());

    private MockHttpServletRequest request(final String aPath) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", aPath);
        request.setRequestURI(aPath);
        return request;
    }

    private ResolvedKey resolved() {
        final ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn("key-row-1");
        return new ResolvedKey(key, mock(User.class));
    }

    // H5 — health is exempt; non-API paths are untouched
    @Test
    void healthAndNonApiPathsAreNotFiltered() {
        assertThat(filter.shouldNotFilter(request("/api/v1/health"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/docs"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/reader"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/download"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/texts"))).isFalse();
    }

    // K7 — a key in the query string is refused before any lookup, even a valid one
    @Test
    void keyInQueryStringIsRefusedWithoutLookup() throws Exception {
        final MockHttpServletRequest request = request("/api/v1/texts");
        request.setQueryString("format=json&key=" + VALID);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("key_in_query_string").contains("rotate");
        verifyNoInteractions(keys, quota);          // Q10: nothing consulted, nothing counted
        verify(chain, never()).doFilter(any(), any());
    }

    // K8 — missing / malformed headers are a 401, never a 500
    @Test
    void missingOrMalformedHeaderIs401() throws Exception {
        for (final String header : new String[] { null, "", "Basic abc", "Bearer", "Bearer   " }) {
            final MockHttpServletRequest request = request("/api/v1/texts");
            if (header != null) request.addHeader("Authorization", header);
            final MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, mock(FilterChain.class));

            assertThat(response.getStatus()).as("header: %s", header).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("missing_key");
        }
        verifyNoInteractions(quota);                // Q10
    }

    // K5 — unknown key
    @Test
    void unknownKeyIs401AndCountsNothing() throws Exception {
        when(keys.resolve(anyString())).thenReturn(Optional.empty());
        final MockHttpServletRequest request = request("/api/v1/texts");
        request.addHeader("Authorization", "Bearer " + VALID);
        final MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid_key");
        verifyNoInteractions(quota);                // Q10
    }

    // K4 + Q4 — success: chain continues, attribute set, headers present
    @Test
    void validKeyProceedsWithRateHeaders() throws Exception {
        final ResolvedKey resolved = resolved();
        when(keys.resolve(VALID)).thenReturn(Optional.of(resolved));
        when(quota.countRequest("key-row-1"))
            .thenReturn(new Decision(true, 20000, 19999, 1_760_000_000L, 0));
        final MockHttpServletRequest request = request("/api/v1/texts");
        request.addHeader("Authorization", "Bearer " + VALID);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(request.getAttribute(ApiAuthFilter.ATTR_KEY)).isSameAs(resolved);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("20000");
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("19999");
        assertThat(response.getHeader("X-RateLimit-Reset")).isEqualTo("1760000000");
    }

    // Q6/Q8 surface — refusal carries 429 + Retry-After + the same headers
    @Test
    void overQuotaIs429WithRetryAfter() throws Exception {
        final ResolvedKey resolved = resolved();   // built outside the when(): no nested stubbing
        when(keys.resolve(VALID)).thenReturn(Optional.of(resolved));
        when(quota.countRequest("key-row-1"))
            .thenReturn(new Decision(false, 20000, 0, 1_760_000_000L, 3600));
        final MockHttpServletRequest request = request("/api/v1/texts");
        request.addHeader("Authorization", "Bearer " + VALID);
        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3600");
        assertThat(response.getContentAsString()).contains("quota_exceeded");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void bearerParsingIsForgiving() {
        final MockHttpServletRequest request = request("/api/v1/texts");
        request.addHeader("Authorization", "bearer   " + VALID + "  ");
        assertThat(ApiAuthFilter.bearerToken(request)).isEqualTo(VALID);
    }
}
