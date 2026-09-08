package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Span;

/**
 * The little environment chip shown beside the version pill (toolbar + landing nav). Amber for
 * anything that is not prod ({@code DEV}, {@code STAGING}, …), muted green for {@code PROD}.
 * The tag is baked in by the build: {@code religioustext.env} resolves from the Maven property
 * {@code rt.env} ({@code dev} by default, {@code prod} under {@code -Pproduction}). Explicit hex
 * colors on purpose — no dependence on Lumo theme variables.
 *
 * @author Christa Claw
 * @version 0.3.6-SNAPSHOT
 * @since 0.3.6
 */
final class EnvBadge {

    private EnvBadge() { }

    /**
     * Build the chip for an environment tag.
     *
     * @param anEnvTag the environment tag ({@code dev}, {@code staging}, {@code prod}, …);
     *                 {@code null}/blank is treated as {@code dev}
     * @return a styled {@link Span} reading e.g. {@code DEV} or {@code PROD}
     */
    static Span of(final String anEnvTag) {
        final String label = (anEnvTag == null || anEnvTag.isBlank()) ? "dev" : anEnvTag.trim();
        final boolean prod = "prod".equalsIgnoreCase(label);
        final Span badge = new Span(label.toUpperCase());
        badge.getStyle()
            .set("font-size", "10px").set("font-weight", "700")
            .set("letter-spacing", "0.05em").set("line-height", "1")
            .set("padding", "4px 7px").set("border-radius", "10px")
            .set("flex-shrink", "0").set("white-space", "nowrap")
            .set("background", prod ? "#d8f0dd" : "#ffe1a3")
            .set("color", prod ? "#1e7a3a" : "#8a5a00")
            .set("border", prod ? "1px solid #9fd6ab" : "1px solid #e6c063");
        return badge;
    }
}
