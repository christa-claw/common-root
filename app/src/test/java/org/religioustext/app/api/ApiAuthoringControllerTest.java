// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.api.ApiAuthoringController.CommentIn;
import org.religioustext.app.api.ApiAuthoringController.NoteIn;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.model.user.User;
import org.religioustext.app.service.AccessService;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.CommentAuthorService;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.PersonalNoteService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The write routes: the key owner's own comments and notes, and nobody else's. */
class ApiAuthoringControllerTest {

    private static final String OWNER = "owner@example.test";

    /** What the hand-rolled services were asked to do. */
    private final List<String> calls = new ArrayList<>();

    private ApiAuthoringController controller(final boolean theCanPublishFlag) {
        final CommentAuthorService authoring = new CommentAuthorService(null) {
            @Override public Authored createOwn(final String e, final String s, final List<VerseComment.Ref> r,
                                                final String c, final boolean p, final boolean d) {
                calls.add("create " + e + " src=" + s + " verses=" + r.size() + " public=" + p + " dictated=" + d);
                return new Authored("cmt_new", c.strip(), p, d, r);
            }
            @Override public Optional<Authored> updateOwn(final String e, final String id, final String c,
                                                          final Boolean p, final Boolean d) {
                calls.add("update " + id + " body=" + c + " public=" + p + " dictated=" + d);
                return "cmt_mine".equals(id)
                    ? Optional.of(new Authored(id, c == null ? "old" : c, Boolean.TRUE.equals(p),
                                               Boolean.TRUE.equals(d), List.of()))
                    : Optional.empty();
            }
            @Override public boolean deleteOwn(final String e, final String id) {
                calls.add("delete " + id);
                return "cmt_mine".equals(id);
            }
        };
        final PersonalNoteService notes = new PersonalNoteService() {
            @Override public void save(final String e, final String s, final String b, final int c, final int v,
                                       final String t, final Boolean d) {
                calls.add("note " + b + "." + c + "." + v + " src=" + s + " dictated=" + d);
            }
            @Override public void delete(final String e, final String b, final int c, final int v) {
                calls.add("unnote " + b + "." + c + "." + v);
            }
        };
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final SourceRow row = mock(SourceRow.class);
        when(row.id()).thenReturn("bible-kjv");
        when(corpus.resolve("kjv")).thenReturn(row);
        final AccessService access = mock(AccessService.class);
        when(access.canPublish(any())).thenReturn(theCanPublishFlag);
        return new ApiAuthoringController(authoring, notes, corpus, access);
    }

    private static MockHttpServletRequest keyed() {
        final User owner = mock(User.class);
        when(owner.getEmail()).thenReturn(OWNER);
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ApiAuthFilter.ATTR_KEY, new ResolvedKey(mock(ApiKey.class), owner));
        return req;
    }

    private static String slug(final org.springframework.http.ResponseEntity<Object> aResponse) {
        return ((ApiError) aResponse.getBody()).error();
    }

    @Test
    @SuppressWarnings("unchecked")
    void createStoresAPrivateCommentOnEveryVerseOfItsRefs() {
        final var res = controller(false).create(
            new CommentIn(List.of("JHN.3.16-17", "Q.2.255"), "  a thought ", null, "kjv", true), keyed());
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getHeaders().getCacheControl()).contains("no-store");
        assertThat(calls).containsExactly("create " + OWNER + " src=bible-kjv verses=3 public=false dictated=true");
        final Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("id", "cmt_new").containsEntry("kind", "personal")
            .containsEntry("state", "private").containsEntry("dictated", true).containsEntry("body", "a thought");
        assertThat((List<String>) body.get("refs")).containsExactly("JHN.3.16", "JHN.3.17", "Q.2.255");
    }

    @Test
    void publishingNeedsTheContributorRole() {
        final CommentIn pub = new CommentIn(List.of("JHN.3.16"), "x", true, null, null);
        assertThat(slug(controller(false).create(pub, keyed()))).isEqualTo("publish_not_permitted");
        assertThat(slug(controller(false).update("cmt_mine", new CommentIn(null, null, true, null, null), keyed())))
            .isEqualTo("publish_not_permitted");
        assertThat(calls).isEmpty();                                   // refused before anything is written
        assertThat(controller(true).create(pub, keyed()).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void createRefusesBadInputWithoutWriting() {
        final var c = controller(true);
        assertThat(slug(c.create(new CommentIn(List.of("JHN.3.16"), " ", null, null, null), keyed()))).isEqualTo("empty_body");
        assertThat(slug(c.create(new CommentIn(List.of("JHN.3.16"), "x".repeat(10_001), null, null, null), keyed())))
            .isEqualTo("body_too_long");
        assertThat(slug(c.create(new CommentIn(List.of(), "x", null, null, null), keyed()))).isEqualTo("bad_ref");
        assertThat(slug(c.create(new CommentIn(List.of("JHN.3"), "x", null, null, null), keyed()))).isEqualTo("bad_ref");
        assertThat(slug(c.create(new CommentIn(List.of("PSA.119.1-176"), "x", null, null, null), keyed())))
            .isEqualTo("too_many_refs");
        assertThat(slug(c.create(new CommentIn(List.of("JHN.3.16"), "x", null, "nope", null), keyed())))
            .isEqualTo("unknown_source");
        assertThat(slug(c.create(new CommentIn(List.of("JHN.3.16"), "x", null, null, null), new MockHttpServletRequest())))
            .isEqualTo("missing_key");
        assertThat(calls).isEmpty();
    }

    @Test
    void updateAndDeleteReachOnlyTheCallersOwnComments() {
        final var c = controller(true);
        assertThat(c.update("cmt_mine", new CommentIn(null, "new text", null, null, null), keyed())
            .getStatusCode().value()).isEqualTo(200);
        assertThat(calls).containsExactly("update cmt_mine body=new text public=null dictated=null");   // absent = unchanged
        assertThat(slug(c.update("cmt_theirs", new CommentIn(null, "x", null, null, null), keyed())))
            .isEqualTo("no_such_comment");
        assertThat(slug(c.update("cmt_mine", new CommentIn(null, null, null, null, null), keyed()))).isEqualTo("bad_request");
        // The marker alone is a valid change.
        assertThat(c.update("cmt_mine", new CommentIn(null, null, null, null, false), keyed())
            .getStatusCode().value()).isEqualTo(200);
        assertThat(c.delete("cmt_mine", keyed()).getStatusCode().value()).isEqualTo(204);
        assertThat(slug(c.delete("cmt_theirs", keyed()))).isEqualTo("no_such_comment");
    }

    @Test
    void notesBelongToOneVerse() {
        final var c = controller(false);
        final var res = c.putNote("jhn.3.16", new NoteIn(" mine ", "kjv", true), keyed());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat((Map<?, ?>) res.getBody()).isEqualTo(Map.of("ref", "JHN.3.16", "body", "mine", "dictated", true));
        assertThat(c.deleteNote("Q.2.255", keyed()).getStatusCode().value()).isEqualTo(204);
        assertThat(calls).containsExactly("note JHN.3.16 src=bible-kjv dictated=true", "unnote 2.1.255");
        assertThat(slug(c.putNote("JHN.3", new NoteIn("x", null, null), keyed()))).isEqualTo("bad_ref");
        assertThat(slug(c.putNote("JHN.3.16-17", new NoteIn("x", null, null), keyed()))).isEqualTo("bad_ref");
        assertThat(slug(c.putNote("JHN.3.16", new NoteIn("", null, null), keyed()))).isEqualTo("empty_body");
        assertThat(slug(c.deleteNote("JHN.3", keyed()))).isEqualTo("bad_ref");
        assertThat(slug(c.putNote("JHN.3.16", new NoteIn("x", null, null), new MockHttpServletRequest())))
            .isEqualTo("missing_key");
    }
}
