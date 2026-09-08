package org.religioustext.app.model.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Publication semantics — and the trap the V14 reseed fell into.
 *
 * {@code makePublic()} routes anything carrying an EXTERNAL reference to {@code pending}
 * (the anti-spam rule for user-authored comments). Every seeded argument carries its source
 * video link, so a seeded row is only publicly visible if it is explicitly approved after
 * its references exist. Getting this wrong made all ~3.6k channel comments vanish sitewide.
 */
class CommentVisibilityTest {

    private static Comment withExternalRef() {
        final Comment c = new Comment();
        c.getReferences().add(CommentReference.external(
            c, 0, "https://youtube.com/watch?v=x", "Channel", "Title"));
        return c;
    }

    @Test
    void makePublicWithAnExternalRefIsPendingAndNotVisible() {
        final Comment c = withExternalRef();
        c.makePublic();
        assertThat(c.getModerationStatus()).isEqualTo(Comment.ModerationStatus.pending);
        assertThat(c.isVisiblePublicly()).isFalse();
    }

    @Test
    void makePublicBeforeRefsExistIsApproved() {
        // The insert path's historical ordering — approved because no refs are attached yet.
        final Comment c = new Comment();
        c.makePublic();
        assertThat(c.getModerationStatus()).isEqualTo(Comment.ModerationStatus.approved);
    }

    @Test
    void approveAfterRefsMakesASeededArgumentVisible() {
        // What DataSeeder.publish() does — order-independent, the invariant that matters.
        final Comment c = withExternalRef();
        c.makePublic();
        c.approve();
        assertThat(c.isVisiblePublicly()).isTrue();
    }

    @Test
    void privateCommentIsNeverVisiblePublicly() {
        final Comment c = withExternalRef();
        c.makePublic();
        c.approve();
        c.makePrivate();
        assertThat(c.isVisiblePublicly()).isFalse();
    }
}
