package org.religioustext.app.config;

import com.vaadin.flow.spring.security.VaadinWebSecurity;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.ui.views.LoginView;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.net.URI;
import java.util.Locale;

/**
 * Spring Security configuration.
 *
 * Extends VaadinWebSecurity which automatically whitelists Vaadin's own
 * resources (JS bundles, icons, PUSH endpoint, WebSocket) and configures
 * Vaadin's CSRF token handling — so we only need to set the login view
 * and any app-specific rules on top.
 *
 * Cookie policy:
 *   - JSESSIONID is the only cookie (session-scoped, no maxAge).
 *   - HttpOnly, SameSite=Lax, and Secure flags set in application.properties.
 *   - No remember-me (per the project's no-client-data privacy policy).
 */
@EnableWebSecurity
@Configuration
public class SecurityConfig extends VaadinWebSecurity {

    @Override
    protected void configure(final HttpSecurity aHttp) throws Exception {
        // Permit static assets before Vaadin's security layer kicks in.
        // favicon.ico, PNGs, and the web-app manifest are all served from
        // META-INF/resources and must be reachable without a session.
        aHttp.authorizeHttpRequests(auth -> auth
            .requestMatchers(
                new AntPathRequestMatcher("/favicon.ico"),
                new AntPathRequestMatcher("/favicon*.png"),
                new AntPathRequestMatcher("/site.webmanifest"),
                new AntPathRequestMatcher("/icons/**"),
                new AntPathRequestMatcher("/images/**"),
                new AntPathRequestMatcher("/docs/**"),
                new AntPathRequestMatcher("/download"),
                new AntPathRequestMatcher("/download/**"),
                // The web API is permitted here and gated by ApiAuthFilter instead:
                // key auth, not session auth (docs/api-spec.md §2). /api/v1/health
                // is the deliberate exception inside that filter.
                new AntPathRequestMatcher("/api/**"),
                new AntPathRequestMatcher("/robots.txt"),
                new AntPathRequestMatcher("/sitemap.xml"),
                new AntPathRequestMatcher("/BingSiteAuth.xml"),
                new AntPathRequestMatcher("/yandex_f23b595a08f9d1ea.html")
            ).permitAll()
            // Language-specific entry points (/fi, /reader/he, ...) are public,
            // like / and /reader themselves: reading requires no account.
            .requestMatchers(languageMatchers()).permitAll()
        );
        super.configure(aHttp);
        setLoginView(aHttp, LoginView.class);

        // The API is stateless and key-authenticated; Vaadin's CSRF token has no
        // place there and would block the POST /api/v1/verify route.
        aHttp.csrf(csrf -> csrf.ignoringRequestMatchers(new AntPathRequestMatcher("/api/**")));

        // Logout: clear session and cookie, then land where the user was.
        // No remember-me cookie is ever issued.
        aHttp.logout(logout -> logout
            .logoutSuccessHandler((request, response, authentication) ->
                response.sendRedirect(logoutTarget(
                    request.getHeader("Referer"), request.getServerName())))
            .invalidateHttpSession(true)
            .deleteCookies("JSESSIONID"));
    }

    /**
     * One matcher per language-specific public entry point: {@code /{lang}} and
     * {@code /reader/{lang}} for every locale in {@link LocaleUtil#LOCALES}. Derived
     * from the same list the language dropdown uses, so a locale added there is
     * automatically reachable without a session. Uses {@code toLanguageTag()} rather
     * than {@code getLanguage()}, which would yield the legacy "iw" for Hebrew.
     */
    private static AntPathRequestMatcher[] languageMatchers() {
        final AntPathRequestMatcher[] matchers = new AntPathRequestMatcher[LocaleUtil.LOCALES.size() * 2];
        int i = 0;
        for (final Locale locale : LocaleUtil.LOCALES) {
            final String lang = locale.toLanguageTag();
            matchers[i++] = new AntPathRequestMatcher("/" + lang);
            matchers[i++] = new AntPathRequestMatcher("/reader/" + lang);
        }
        return matchers;
    }

    /**
     * Where a sign-out lands. Reading requires no account, so signing out INSIDE the reader
     * should leave the reader open — now as a guest — instead of bouncing to the landing
     * page and losing the columns. The originating page comes from the {@code Referer}
     * header, which survives the session invalidation that rules out a session stash.
     *
     * <p>Honoured ONLY when the referer is same-origin and points into {@code /reader}, so a
     * spoofed or off-site referer can never turn this into an open redirect. Anything else —
     * another page, a foreign host, a malformed or absent header — falls back to {@code "/"}.
     *
     * @param aReferer    the request's {@code Referer} header (may be {@code null})
     * @param aServerName the current request's server name, for the same-origin check
     * @return the path to redirect to after logout
     */
    static String logoutTarget(final String aReferer, final String aServerName) {
        if (aReferer == null || aReferer.isBlank()) return "/";
        final URI uri;
        try {
            uri = URI.create(aReferer.trim());
        } catch (final IllegalArgumentException ex) {
            return "/";
        }
        // A host of null means a relative referer — same-origin by definition.
        if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(aServerName)) return "/";
        final String path = uri.getPath();
        if (path == null || !path.startsWith("/reader")) return "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
