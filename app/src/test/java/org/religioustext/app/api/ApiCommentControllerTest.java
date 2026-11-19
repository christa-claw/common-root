// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.user.ApiKey;
import org.religioustext.app.model.user.User;
import org.religioustext.app.service.ApiCorpusService;
import org.religioustext.app.service.ApiKeyService.ResolvedKey;
import org.religioustext.app.service.CommentAuthorService;
import org.religioustext.app.service.CommentQueryService;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.PersonalNoteService;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The comments route's contract: kinds, the edition gate, privacy, one appearance per comment. */
class ApiCommentControllerTest {

    private static final String OWNER = "owner@example.test";

    private static VerseComment comment(final String anId, final String aLabel, final boolean theOwnFlag,
                                        final boolean theUnpublishedFlag) {
        return new VerseComment(anId, "body of " + anId, "https://youtu.be/x?t=42",
            "https://youtu.be/x", aLabel, "A title",
            List.of(new VerseComment.Ref(false, "JHN", 3, 16), new VerseComment.Ref(false, "ROM", 5, 8)),
            theOwnFlag, theUnpublishedFlag, null);
    }

    /** Public: cmt_pub on 16 and 17. Translator (src only): cmt_note on 16. Own: cmt_mine (draft) on 16. */
    private static ApiCommentController controller(final String aLicense) {
        final CommentQueryService comments = new CommentQueryService() {
            @Override public Map<String, List<VerseComment>> forChapter(final String b, final int c, final String s) {
                assertThat(s).isNull();          // the channel read never names an edition
                final VerseComment pub = comment("cmt_pub", "Some Channel", false, false);
                return Map.of("16", List.of(pub), "17", List.of(pub), "30", List.of(comment("cmt_far", "X", false, false)));
            }
            @Override public Map<String, List<VerseComment>> translatorNotesForChapter(
                    final String b, final int c, final String s) {
                return "bible-lut".equals(s) ? Map.of("16", List.of(comment("cmt_note", null, false, false))) : Map.of();
            }
            @Override public Map<String, List<VerseComment>> ownForChapter(final String e, final String b, final int c) {
                return OWNER.equals(e) ? Map.of("16", List.of(comment("cmt_mine", null, true, true))) : Map.of();
            }
            @Override public List<String> knownVoices() { return List.of("Some Channel"); }
        };
        final PersonalNoteService notes = new PersonalNoteService() {
            @Override public Map<String, Note> detailedForChapter(final String e, final String b, final int c) {
                return OWNER.equals(e)
                    ? Map.of("16", new Note("my note", true), "2", new Note("elsewhere", false)) : Map.of();
            }
        };
        final ApiCorpusService corpus = mock(ApiCorpusService.class);
        final SourceRow row = mock(SourceRow.class);
        when(row.id()).thenReturn("bible-lut");
        when(row.abbreviation()).thenReturn("LUT");
        when(row.license()).thenReturn(aLicense);
        when(corpus.resolve("lut")).thenReturn(row);
        final CommentAuthorService authoring = new CommentAuthorService(null) {
            @Override public java.util.Set<String> dictatedAmong(final String e, final java.util.Collection<String> ids) {
                return OWNER.equals(e) && ids.contains("cmt_mine") ? java.util.Set.of("cmt_mine") : java.util.Set.of();
            }
        };
        return new ApiCommentController(comments, authoring, notes, corpus, new AppReadPolicy(java.time.Clock.systemUTC(), 300));
    }

    private static MockHttpServletRequest keyed() {
        final User owner = mock(User.class);
        when(owner.getEmail()).thenReturn(OWNER);
        final MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ApiAuthFilter.ATTR_KEY, new ResolvedKey(mock(ApiKey.class), owner));
        return req;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(final ResponseEntity<Object> aResponse, final String aField) {
        return (List<Map<String, Object>>) ((Map<String, Object>) aResponse.getBody()).get(aField);
    }

    @Test
    void defaultIsChannelCommentsOnceEachWithAttribution() {
        final var res = controller("Public domain").comments("JHN.3.16-17", null, null, null, keyed());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getHeaders().getCacheControl()).contains("public");
        final var comments = list(res, "comments");
        assertThat(comments).extracting(m -> m.get("id")).containsExactly("cmt_pub");   // cited twice, served once
        assertThat(comments.get(0)).containsEntry("kind", "channel").containsEntry("channel", "Some Channel")
            .containsEntry("video", "https://youtu.be/x?t=42")
            .containsEntry("url", "https://common-root.org/reader?comment=cmt_pub")
            .doesNotContainKey("state").doesNotContainKey("dictated");   // the marker is the author's alone
        assertThat((List<String>) comments.get(0).get("refs")).containsExactly("JHN.3.16", "ROM.5.8");
        assertThat(((Map<?, ?>) res.getBody()).containsKey("notes")).isFalse();
    }

    @Test
    void srcAddsThatEditionsTranslatorNotes() {
        final var comments = list(controller("Public domain").comments("JHN.3.16", null, "lut", null, keyed()), "comments");
        assertThat(comments).extracting(m -> m.get("kind")).containsExactly("channel", "translator");
        assertThat(comments.get(1)).doesNotContainKey("channel").doesNotContainKey("video");
    }

    @Test
    void translatorNeedsAnEditionAndRespectsItsLicence() {
        assertThat(((ApiError) controller("Public domain").comments("JHN.3.16", "translator", null, null, keyed())
            .getBody()).error()).isEqualTo("src_required");
        assertThat(controller("Public domain").comments("JHN.3.16", "translator", "nope", null, keyed())
            .getStatusCode().value()).isEqualTo(404);
        assertThat(((ApiError) controller("Copyright 2011 Biblica").comments("JHN.3.16", "translator", "lut", null, keyed())
            .getBody()).error()).isEqualTo("not_redistributable");
    }

    @Test
    void personalIsTheKeyOwnersAloneAndNeverCached() {
        final var res = controller("Public domain").comments("JHN.3.16", "personal", null, null, keyed());
        assertThat(res.getHeaders().getCacheControl()).contains("no-store").contains("private");
        final var comments = list(res, "comments");
        assertThat(comments).extracting(m -> m.get("id")).containsExactly("cmt_mine");
        assertThat(comments.get(0)).containsEntry("kind", "personal").containsEntry("state", "private")
            .containsEntry("dictated", true);
        assertThat(list(res, "notes"))
            .containsExactly(Map.of("ref", "JHN.3.16", "body", "my note", "dictated", true));

        // No resolved key: nothing personal is reachable.
        assertThat(controller("Public domain").comments("JHN.3.16", "personal", null, null, new MockHttpServletRequest())
            .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void badInputIsRefusedWhole() {
        final var c = controller("Public domain");
        assertThat(((ApiError) c.comments("JHN.3.16", "sermons", null, null, keyed()).getBody()).error()).isEqualTo("bad_kind");
        assertThat(((ApiError) c.comments("nonsense", null, null, null, keyed()).getBody()).error()).isEqualTo("bad_ref");
        assertThat(((ApiError) c.comments("ISA.52.13-53.12", null, null, null, keyed()).getBody()).error()).isEqualTo("bad_ref");
        final String many = "JHN.1," .repeat(ApiCommentController.MAX_REFS + 1);
        assertThat(((ApiError) c.comments(many, null, null, null, keyed()).getBody()).error()).isEqualTo("too_many_refs");
    }

    @Test
    void channelsListsTheKnownVoices() {
        final var body = (Map<?, ?>) controller("Public domain").channels().getBody();
        assertThat(body.get("count")).isEqualTo(1);
        assertThat(body.get("channels")).isEqualTo(List.of("Some Channel"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsViewCountsEveryVerseOfTheChapterWithoutBodies() {
        final var res = controller("Public domain").comments("JHN.3", null, null, "counts", keyed());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        final Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).doesNotContainKey("comments").doesNotContainKey("notes");
        assertThat((Map<String, Integer>) body.get("counts"))
            .containsExactly(Map.entry("JHN.3.16", 1), Map.entry("JHN.3.17", 1), Map.entry("JHN.3.30", 1));

        // with an edition, its translator notes count too; personal never does
        final var withNotes = (Map<String, Object>) controller("Public domain")
            .comments("JHN.3.16", "channel,translator,personal", "lut", "counts", keyed()).getBody();
        assertThat((Map<String, Integer>) withNotes.get("counts")).containsExactly(Map.entry("JHN.3.16", 2));

        assertThat(((ApiError) controller("Public domain").comments("JHN.3", null, null, "all", keyed())
            .getBody()).error()).isEqualTo("bad_view");
    }
}
