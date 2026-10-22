// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import java.util.List;

/**
 * People credited on the About page. Adding a contributor is one line here plus
 * an {@code about.contributors.<handle>} key in every translations bundle.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
public final class Contributors {

    /**
     * One credited contributor.
     *
     * @param handle GitHub username; also the suffix of the i18n description key
     */
    public record Contributor(String handle) {
        /** @return the GitHub profile URL */
        public String url() {
            return "https://github.com/" + handle;
        }

        /** @return the i18n key holding what this person contributed */
        public String descriptionKey() {
            return "about.contributors." + handle;
        }
    }

    /** Contributors, oldest contribution first. */
    public static final List<Contributor> ALL = List.of(
        new Contributor("slsgzs-cloud"));

    private Contributors() {
    }
}
