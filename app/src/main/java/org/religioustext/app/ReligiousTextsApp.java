package org.religioustext.app;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Inline;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Common Root? — Vaadin application entry point.
 * (Internal class/theme names retain the original "religious-texts" slug;
 *  the user-facing app name is "Common Root?".)
 */
@SpringBootApplication
@Theme(value = "religious-texts", variant = Lumo.LIGHT)
public class ReligiousTextsApp implements AppShellConfigurator {

    /** Umami analytics (self-hosted, first-party, cookieless). The tracking
     *  script is injected ONLY when both properties are set — they are unset by
     *  default, so local/dev runs never track. Prod sets them via env
     *  (RELIGIOUSTEXT_UMAMI_SRC / RELIGIOUSTEXT_UMAMI_WEBSITE_ID in
     *  docker-compose.prod.yml). Umami stores no cookies and no raw IPs —
     *  aggregate, first-party analytics consistent with the site's privacy
     *  stance; no consent banner required. */
    @Value("${religioustext.umami.src:}")
    private String umamiSrc;

    @Value("${religioustext.umami.website-id:}")
    private String umamiWebsiteId;

    @Override
    public void configurePage(final AppShellSettings settings) {
        // ── SEO / link previews ────────────────────────────────────────
        // The default <title> only. Everything else — description, canonical,
        // og:*, twitter:* — is written per-request by SocialPreviewInitListener
        // (and by LandingSeoListener for the /read/:code pages), because those
        // tags depend on the request path and this method does not see it.
        //
        // Do NOT re-add meta tags here. Until 2026-07-28 this method emitted a
        // description and a full og:*/twitter:card set, and the listener emitted
        // its own on top: every non-landing page shipped TWO conflicting
        // <meta name="description"> tags (Google picks one arbitrarily) and a
        // duplicate og: set. Worse, AppShellSettings.addMetaTag can only write
        // name="og:...", which OpenGraph parsers ignore — the tags added here
        // were dead weight that only served to collide with the correct
        // property="og:..." ones.
        settings.setPageTitle("Common Root? — read the Bible, Quran and Hadith side by side");

        if (umamiSrc != null && !umamiSrc.isBlank()
                && umamiWebsiteId != null && !umamiWebsiteId.isBlank()) {
            settings.addInlineWithContents(Inline.Position.APPEND,
                "<script defer src=\"" + umamiSrc + "\" data-website-id=\""
                    + umamiWebsiteId + "\"></script>",
                Inline.Wrapping.NONE);
        }
    }

    public static void main(final String[] args) {
        SpringApplication.run(ReligiousTextsApp.class, args);
    }
}
