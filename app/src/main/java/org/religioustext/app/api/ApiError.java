package org.religioustext.app.api;

/**
 * The one JSON error shape every {@code /api} response uses (docs/api-spec.md §7)
 * — including when the caller asked for {@code format=text}: an error is not a
 * passage.
 *
 * @param status  the HTTP status, repeated in the body so a logged body is
 *                self-describing
 * @param error   a stable machine-readable slug ({@code invalid_key},
 *                {@code quota_exceeded}, …) — clients switch on this, never on
 *                the message
 * @param message one human sentence
 * @param docs    where the docs page explains this case
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
public record ApiError(int status, String error, String message, String docs) {

    /** The docs page anchor base; route docs hang off it. */
    public static final String DOCS_BASE = "https://common-root.org/api/docs";

    /**
     * Convenience constructor pointing at a docs anchor.
     *
     * @param aStatus  the HTTP status
     * @param anError  the machine-readable slug
     * @param aMessage the human sentence
     * @param anAnchor the anchor under the docs page ("auth", "quotas", …)
     * @return the error body
     */
    public static ApiError of(final int aStatus, final String anError,
                              final String aMessage, final String anAnchor) {
        return new ApiError(aStatus, anError, aMessage, DOCS_BASE + "#" + anAnchor);
    }
}
