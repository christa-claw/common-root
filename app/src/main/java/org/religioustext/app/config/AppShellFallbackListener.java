// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.config;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.communication.IndexHtmlResponse;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.i18n.TranslationProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Puts a visible message in the served HTML for the reader that never boots.
 *
 * <h2>The failure this fixes</h2>
 * Reported by Maria Lehtonen (Kotus) on 2026-11-03 and answered on 2026-11-04
 * with a promise to fix it. Behind her institute's filtering proxy the site
 * showed <em>a plain white page with no notice at all</em>. The server's HTML
 * arrived; the JavaScript bundle did not. A reader in that position cannot tell
 * a block from a broken site, and neither can we — which is why her description
 * of the symptom was worth more than a bug report.
 *
 * <h2>Why {@code <noscript>} alone does not solve it</h2>
 * {@code <noscript>} renders only when scripting is DISABLED. In this failure
 * scripting is enabled and working — the browser simply never receives
 * {@code /VAADIN/build/*.js}, because something between it and us refused the
 * request. So the content of a {@code <noscript>} block is exactly what such a
 * reader never sees. (The {@code <noscript>} blocks in {@link
 * LandingSeoListener} serve a different purpose: crawlers that do not execute
 * JavaScript at all.)
 *
 * <h2>How this works instead</h2>
 * A single {@code <div id="cr-noboot">} is appended to the body, inline-styled
 * and {@code display:none}. Nothing external is referenced — no stylesheet, no
 * font, no image — because in this failure mode nothing external loads. Three
 * independent triggers reveal it:
 *
 * <ol>
 *   <li><b>A failed bundle load.</b> An inline capture-phase {@code error}
 *       listener fires for resources that fail, with the failing element as
 *       {@code event.target}. It reveals the message only for a SCRIPT or LINK
 *       pointing at {@code /VAADIN/}, so an unrelated broken image cannot
 *       trigger it.</li>
 *   <li><b>A silent hang.</b> A proxy that black-holes the request produces no
 *       error event at all, so a timer reveals the message if the UI has not
 *       rendered within {@value #REVEAL_AFTER_MS} ms. Boot is detected from DOM
 *       EVIDENCE — whether the body holds any element that is not a script,
 *       style or this listener's own markup — and deliberately NOT from
 *       {@code window.Vaadin}, which the inline bootstrap sets before the
 *       bundle is fetched and which is therefore still present in precisely
 *       the failure we are detecting. A framework-agnostic test also cannot
 *       rot against a Vaadin upgrade. The cost of getting this wrong is
 *       asymmetric: a false positive covers a WORKING page for every reader,
 *       so the test errs towards staying hidden.</li>
 *   <li><b>Scripting disabled.</b> A {@code <noscript>} wrapping a one-rule
 *       {@code <style>} un-hides the same div, so the text exists once in the
 *       document rather than twice.</li>
 * </ol>
 *
 * Once the client does boot the div stays in the DOM, still {@code display:
 * none}. It never needs removing, so there is no race with Vaadin's own
 * rendering and nothing to clean up.
 *
 * <h2>Ordering against the other listeners</h2>
 * Vaadin's Spring integration collects {@code VaadinServiceInitListener} beans
 * with {@code getBeansOfType(..)}, which does not honour {@code @Order} — see
 * the discussion in {@link LandingSeoListener}. This listener is safe in any
 * order because it only APPENDS to {@code <body>}: it reads nothing, removes
 * nothing, and touches no {@code <head>} tag, so it cannot clobber either
 * {@code LandingSeoListener} or {@code SocialPreviewInitListener}, and neither
 * can clobber it. It also runs on every path rather than claiming a path
 * domain, which is deliberate: the blank page is not route-specific.
 */
@Component
public class AppShellFallbackListener implements VaadinServiceInitListener {

    /** How long to wait for the client to boot before showing the message. */
    private static final int REVEAL_AFTER_MS = 8000;

    private final TranslationProvider translations;

    public AppShellFallbackListener(final TranslationProvider aTranslations) {
        this.translations = aTranslations;
    }

    @Override
    public void serviceInit(final ServiceInitEvent anEvent) {
        anEvent.addIndexHtmlRequestListener(this::injectFallback);
    }

    private void injectFallback(final IndexHtmlResponse aResponse) {
        try {
            final Locale locale = resolveLocale(aResponse);
            final Document doc = aResponse.getDocument();
            final boolean rtl = LocaleUtil.isRtl(locale);

            final Element box = doc.body().appendElement("div");
            box.attr("id", "cr-noboot");
            box.attr("dir", rtl ? "rtl" : "ltr");
            box.attr("style", "display:none;position:fixed;inset:0;z-index:2147483647;"
                    + "margin:0;padding:2rem 1.25rem;overflow:auto;"
                    + "background:#fff;color:#03153B;"
                    + "font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
                    + "font-size:16px;line-height:1.55;"
                    + "text-align:" + (rtl ? "right" : "left") + ";");

            final Element inner = box.appendElement("div");
            inner.attr("style", "max-width:34rem;margin:10vh auto 0;");

            final Element h = inner.appendElement("h1");
            h.attr("style", "font-size:1.35rem;font-weight:600;margin:0 0 .75rem;");
            h.text(t("shell.blocked.title", locale));

            final Element p = inner.appendElement("p");
            p.attr("style", "margin:0 0 1rem;");
            p.text(t("shell.blocked.body", locale));

            final Element hint = inner.appendElement("p");
            hint.attr("style", "margin:0;color:#4a5568;font-size:.95rem;");
            hint.text(t("shell.blocked.hint", locale));

            // Scripting disabled: un-hide the SAME div rather than repeat its text.
            doc.body().appendElement("noscript")
                    .appendElement("style")
                    .text("#cr-noboot{display:block!important}");

            doc.body().appendElement("script").text(SCRIPT);
        } catch (final RuntimeException e) {
            // A missing fallback must never take the page down with it.
            // Worst case we are back to the old behaviour, which is the bug.
            org.slf4j.LoggerFactory.getLogger(AppShellFallbackListener.class)
                    .warn("could not inject the app-shell fallback", e);
        }
    }

    private String t(final String aKey, final Locale aLocale) {
        return translations.getTranslation(aKey, aLocale);
    }

    /**
     * The locale for a request that has no session yet: an explicit {@code
     * ?lang=} first, because a shared link carries its own language, then
     * whatever the browser asked for, then English.
     */
    private Locale resolveLocale(final IndexHtmlResponse aResponse) {
        final String tag = aResponse.getVaadinRequest().getParameter("lang");
        final Locale fromTag = tag == null ? null : LocaleUtil.fromTag(tag);
        if (fromTag != null) return fromTag;
        final Locale requested = aResponse.getVaadinRequest().getLocale();
        if (requested != null) {
            for (final Locale known : LocaleUtil.LOCALES) {
                if (known.getLanguage().equals(requested.getLanguage())) return known;
            }
        }
        return LocaleUtil.EN;
    }

    private static final String SCRIPT =
            "(function(){var b=document.getElementById('cr-noboot');if(!b)return;"
          + "var shown=false;function show(){if(shown)return;shown=true;b.style.display='block';}"
          // Did anything actually render? Count body children that are neither our
          // own markup nor inert head-ish tags. Framework-agnostic on purpose: see
          // the class javadoc on why window.Vaadin is the wrong signal here.
          + "function booted(){var k=document.body.children,i,e,n;"
          + "for(i=0;i<k.length;i++){e=k[i];n=e.tagName;"
          + "if(n==='SCRIPT'||n==='NOSCRIPT'||n==='STYLE'||n==='TEMPLATE'||n==='LINK')continue;"
          + "if(e.id==='cr-noboot')continue;return true;}return false;}"
          + "window.addEventListener('error',function(e){var t=e&&e.target;if(!t)return;"
          + "var n=t.tagName;if(n!=='SCRIPT'&&n!=='LINK')return;"
          + "var u=t.src||t.href||'';if(u.indexOf('/VAADIN/')<0)return;"
          // Even on a failed bundle, only speak up if nothing has rendered.
          + "if(!booted())show();},true);"
          + "setTimeout(function(){if(!booted())show();}," + REVEAL_AFTER_MS + ");})();";
}
