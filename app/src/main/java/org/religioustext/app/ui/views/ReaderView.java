package org.religioustext.app.ui.views;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.spring.security.AuthenticationContext;
import org.religioustext.app.model.DisplayOptions;
import org.religioustext.app.model.DisplayOptions.OrderMode;
import org.religioustext.app.model.SourceColumn;
import org.religioustext.app.model.VerseRef;
import org.religioustext.app.ui.views.reader.SourceCatalog;
import org.religioustext.app.ui.views.reader.SourceRow;
import org.religioustext.app.service.TextQueryService;
import org.religioustext.app.service.CommentAclService;
import org.religioustext.app.service.CommentAuthorService;
import org.religioustext.app.service.CommentQueryService;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.PersonalNoteService;
import org.religioustext.app.service.SearchService;
import org.religioustext.app.service.UserPreferencesService;
import org.religioustext.app.service.UserService;
import org.religioustext.app.model.user.UserPreferences;
import org.religioustext.app.ui.components.LanguageSelect;
import org.religioustext.app.ui.components.SearchDialog;
import org.religioustext.app.i18n.LocaleUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-column scripture reader.
 *
 * UNIFIED VERSE-WINDOW MODEL (rewritten 2026-06-08).
 * One lazy-load primitive for all orderings and display modes: a fixed-size
 * window of verses ordered by whichever sequence the active OrderMode names
 * (@globalCanonicalSeq / @globalChronologicalSeq / @globalNarrativeSeq). The
 * previous design had two separate paths — a book/chapter loader for canonical
 * and a (book,chapter)-chunk loader for chronological — which made scroll speed
 * vary wildly between long books (Genesis) and short/interleaved ones (Psalms,
 * chronological weave). A constant verse count per load makes every batch add a
 * near-constant amount of content; the pixel-based scroll trigger then adapts to
 * the rendered height, so scrolling feels uniform everywhere.
 *
 * Display modes (Scriptio variants, Chapters, Verses, Titles) are now PURELY a
 * rendering concern over the verse stream — they no longer affect loading. A
 * book separator is emitted wherever consecutive verses cross a book boundary; a
 * chapter heading wherever they cross a chapter boundary; both derived by walking
 * the window, not tracked as separate load state.
 */
@Route("reader")
@PageTitle("Common Root? — Reader")
@com.vaadin.flow.server.auth.AnonymousAllowed
public class ReaderView extends VerticalLayout implements BeforeEnterObserver {

    // Verses visible on a typical screen ≈ 30. Buffer = 2× viewport each side,
    // so the DOM window ≈ 30 visible + 60 above + 60 below = 150. One named knob.
    static final int WINDOW_VERSES = 150;
    // Forward/backward batch size — how many verses one load adds. Half a buffer
    // so two loads refill a side. Kept ≤ WINDOW so a single load never overfills.
    static final int BATCH_VERSES  = 60;

    // GitHub Sponsors target for the toolbar ♥ link (also on the About nav).
    private static final String SPONSOR_URL = "https://github.com/sponsors/christa-claw";

    // ?comments=_all — "panel open, no commenter filter". Unreserved URL characters ONLY:
    // the first cut used '*', a sub-delimiter that did not survive the Location parse and
    // QueryParameters re-serialisation of the post-login forward, so the panel silently
    // vanished on return. No channel is named "_all".
    static final String COMMENTS_ALL = "_all";

    // Unfiltered comments-panel page size: enough to browse recent voices, small enough to
    // open instantly (cards + the bulk permission evaluation both scale with it). "Show more"
    // appends the next page. A filtered view is never capped.
    private static final int UNFILTERED_CARD_CAP = 50;

    // HTTP-session attribute carrying the reader link to return to after a sign-in started
    // from the reader. Written here at Sign-In click time (the live view serialized by
    // buildCurrentLinkPath); consumed by AboutView — the post-login landing — which forwards
    // to it. Session attributes survive login (session-fixation protection changes only the id).
    static final String POST_LOGIN_REDIRECT_ATTR = "cr.postLoginRedirect";

    // Flag emoji per language code (matches LanguageSelect), prefixed to each
    // source in the picker so the reader can scan the list by language at a glance.
    private static final java.util.Map<String,String> LANG_FLAGS = java.util.Map.ofEntries(
        java.util.Map.entry("en", "\uD83C\uDDEC\uD83C\uDDE7"),
        java.util.Map.entry("ar", "\uD83C\uDDF8\uD83C\uDDE6"),
        java.util.Map.entry("es", "\uD83C\uDDEA\uD83C\uDDF8"),
        java.util.Map.entry("fi", "\uD83C\uDDEB\uD83C\uDDEE"),
        java.util.Map.entry("sv", "\uD83C\uDDF8\uD83C\uDDEA"),
        java.util.Map.entry("ru", "\uD83C\uDDF7\uD83C\uDDFA"),
        java.util.Map.entry("zh", "\uD83C\uDDE8\uD83C\uDDF3"),
        java.util.Map.entry("fr", "\uD83C\uDDEB\uD83C\uDDF7"),
        java.util.Map.entry("it", "\uD83C\uDDEE\uD83C\uDDF9"),
        java.util.Map.entry("de", "\uD83C\uDDE9\uD83C\uDDEA"),
        java.util.Map.entry("hi", "\uD83C\uDDEE\uD83C\uDDF3"),
        java.util.Map.entry("hlt", "\uD83C\uDDF2\uD83C\uDDF2"),
        java.util.Map.entry("he", "\uD83C\uDDEE\uD83C\uDDF1"),
        java.util.Map.entry("grc", "\uD83C\uDDEC\uD83C\uDDF7"),
        java.util.Map.entry("la", "\uD83C\uDDFB\uD83C\uDDE6"));

    /** Flag-emoji prefix (with trailing space) for a source's language code, or
     *  "" when the language is unknown/blank. */
    private static String flagFor(final String aLang) {
        if (aLang == null || aLang.isBlank()) return "";
        final String f = LANG_FLAGS.get(aLang.toLowerCase());
        return f == null ? "" : f + " ";
    }

    /** Source-picker sort rank by text type: Bibles first, then the Qur'an, then hadith. */
    private static int typeRank(final String aType) {
        if ("bible".equals(aType)) return 0;
        if ("quran".equals(aType)) return 1;
        return 2;
    }

    /** Text types that carry a paired companion translation shown beneath the
     *  primary. Both the Qur'an and hadith store their translations as separate
     *  docs whose @baseText points back to the Arabic, and align by
     *  {@code @globalCanonicalSeq} — so they share the overlay, the translation picker,
     *  the in-picker flag, and the primary-source exclusion. */
    private static boolean hasCompanions(final String aType) {
        return "quran".equals(aType) || "hadith".equals(aType);
    }

    // Tier-1 language picker label: flag + native language name.
    private static final java.util.Map<String,String> LANG_NAMES = java.util.Map.ofEntries(
        java.util.Map.entry("en", "English"),  java.util.Map.entry("ar", "\u0627\u0644\u0639\u0631\u0628\u064A\u0629"),
        java.util.Map.entry("es", "Espa\u00F1ol"), java.util.Map.entry("fi", "Suomi"),
        java.util.Map.entry("sv", "Svenska"),  java.util.Map.entry("ru", "\u0420\u0443\u0441\u0441\u043A\u0438\u0439"),
        java.util.Map.entry("zh", "\u4E2D\u6587"),    java.util.Map.entry("fr", "Fran\u00E7ais"),
        java.util.Map.entry("it", "Italiano"), java.util.Map.entry("de", "Deutsch"),
        java.util.Map.entry("hi", "\u0939\u093F\u0928\u094D\u0926\u0940"),
        java.util.Map.entry("hlt", "Matu Chin"),
        java.util.Map.entry("he", "\u05E2\u05D1\u05E8\u05D9\u05EA"),
        java.util.Map.entry("grc", "\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC"),
        java.util.Map.entry("la", "Latina"));

    private static String langLabel(final String aCode) {
        return flagFor(aCode) + LANG_NAMES.getOrDefault(aCode, aCode == null ? "" : aCode);
    }

    // A trailing "(English)"/"(Arabic)" gloss on a text's name is redundant once the
    // language is chosen in tier 1; strip it for the tier-2 label (display only).
    private static final java.util.Set<String> LANG_GLOSSES = java.util.Set.of(
        "English","Arabic","Spanish","Finnish","Swedish","Russian","Chinese","French","Italian","German","Hindi",
        "Matu Chin","Hebrew","Greek","Latin");
    private static String stripLangGloss(final String aName) {
        if (aName == null) return "";
        final int p = aName.lastIndexOf('(');
        if (p > 0 && aName.endsWith(")")
                && LANG_GLOSSES.contains(aName.substring(p + 1, aName.length() - 1).trim()))
            return aName.substring(0, p).trim();
        return aName;
    }

    private final TextQueryService   queryService;
    private final CommentQueryService commentService;
    private final CommentAuthorService commentAuthor;
    private final CommentAclService commentAcl;
    private final PersonalNoteService noteService;
    private final SearchService      searchService;
    private final UserPreferencesService prefsService;
    private final UserService userService;
    private final AuthenticationContext authContext;
    private final BuildInfo          buildInfo;
    private final List<SourceColumn> columns = new ArrayList<>();

    // Comment-driven browsing (toolbar 💬): a pinned panel listing every public
    // comment; its ref buttons drive the text by opening new columns. The
    // inverse of the per-verse 💬 badges, which are text-driven.
    private final Div commentsPanel = new Div();
    private boolean   commentsPanelBuilt;
    /** The panel's commenter filter — a field so deep links ({@code ?comments=<channel>})
     *  can preset it. Assigned in buildCommentsPanel. */
    private com.vaadin.flow.component.combobox.ComboBox<String> commentsChannelFilter;
    /** Every public comment, loaded once when the panel is built — a field so the
     *  ?comment=cmt_… deep link can resolve its target without a second query. */
    private List<VerseComment> commentsAll;
    /** The currently-focused comment (?comment=cmt_…): set by a comment deep
     *  link and by using a card's ref / Open-all buttons, cleared when the panel
     *  closes. While the panel is visible, the toolbar Copy-link carries it — so
     *  "open a comment's passages, hit Copy link" reproduces the whole view:
     *  columns + panel + filter + focused comment, in one URL. */
    private String focusedCommentId;
    private final List<ColState>     states  = new ArrayList<>();
    private final List<String[]>     sources;
    private final SourceCatalog      catalog;
    private final Div                columnsLayout;

    // Guaranteed-unique per-column id (identityHashCode can collide and would
    // make two columns share DOM ids, silently breaking the second's observer).
    private int    columnSeq      = 0;

    // Shared sync position, now keyed on the active SEQ value rather than
    // (book, chapter). Seq is precise even where the same (book, chapter)
    // reappears in an interleaved chronological order. Each column resolves the
    // shared seq into its own DOM position (it may be loaded → scroll, or not →
    // jump). currentBook/currentChapter are kept only for the human-readable
    // label and the book dropdown.
    private int    currentSeq     = -1;
    private String currentBook    = null;
    private int    currentChapter = 1;

    // Toolbar Copy-link button, kept as a field so its client-side copy handler
    // can be fed the current link path as the view changes.
    private Button copyLinkBtn;

    // Toolbar 💬 toggle — a field so every open/close path (toolbar click, panel
    // ✕, deep links) can restyle it to reflect the panel's actual state.
    private Button commentsBtn;

    /** Muted when the panel is closed, primary when open — the toggle's colour
     *  IS its state, matching the per-column eye/comment toggles. */
    private void updateCommentsBtn() {
        if (commentsBtn == null) return;
        commentsBtn.getStyle().set("color", commentsPanel.isVisible()
            ? "var(--lumo-primary-color)" : "var(--lumo-secondary-text-color)");
    }

    // Per-column infinite-scroll machinery (sentinels, client observers, jump JS)
    // extracted to ReaderScrollController; it calls back for loads + sync below.
    private final ReaderScrollController scrollController;
    // Verse-window rendering + forward/backward fill, extracted to
    // VerseWindowRenderer; ReaderView drives it from openAtSeq and the scroll cbs.
    private final VerseWindowRenderer renderer;

    // The signed-in user's saved reader preferences (null when signed out or
    // never saved). Loaded once per view in beforeEnter; feeds the no-link
    // defaults, the src-less-link fallback edition, and the resume position.
    private UserPreferences prefs;
    /** Voices this reader has muted (V17) — parsed once from {@link #prefs} so
     *  the per-verse render path never re-parses. Empty when signed out: muting is
     *  an account preference, and an anonymous visitor sees every voice. */
    private java.util.Set<String> mutedVoices = java.util.Set.of();
    private boolean         prefsLoaded;
    private long            lastPositionSaveMs;

    // Per-column reader state (book list, companion pairing, verse-window
    // bounds, controls, attribution) extracted to ColState.java (same package).

    public ReaderView(final TextQueryService aQueryService,
                      final CommentQueryService aCommentService,
                      final CommentAuthorService aCommentAuthor,
                      final CommentAclService aCommentAcl,
                      final PersonalNoteService aNoteService,
                      final SearchService aSearchService,
                      final UserPreferencesService aPrefsService,
                      final UserService aUserService,
                      final AuthenticationContext anAuthContext,
                      final BuildInfo aBuildInfo) {
        this.queryService = aQueryService;
        this.commentService = aCommentService;
        this.commentAuthor = aCommentAuthor;
        this.commentAcl   = aCommentAcl;
        this.noteService  = aNoteService;
        this.searchService = aSearchService;
        this.prefsService = aPrefsService;
        this.userService  = aUserService;
        this.authContext  = anAuthContext;
        this.buildInfo    = aBuildInfo;
        this.sources       = aQueryService.listSources();
        this.catalog       = new SourceCatalog(this.sources);
        this.columnsLayout = new Div();
        this.renderer = new VerseWindowRenderer(aQueryService, aCommentService, aNoteService,
            anAuthContext, new VerseWindowRenderer.Host() {
                @Override public int activeSeq(final ColState aState, final VerseRef aVerseRef) {
                    return ReaderView.this.activeSeq(aState, aVerseRef);
                }
                @Override public String bookHeading(final ColState aState, final String aBookName) {
                    return ReaderView.this.bookHeading(aState, aBookName);
                }
                @Override public Span commentBadge(final ColState aState, final String aBookName,
                        final int aChapter, final String aVerseNo, final List<VerseComment> theComments) {
                    return ReaderView.this.commentBadge(aState, aBookName, aChapter, aVerseNo, theComments);
                }
                @Override public Span noteBadge(final ColState aState, final String anEmail,
                        final String aBookName, final String aBookCode, final int aChapter,
                        final String aVerseNo, final String anExisting) {
                    return ReaderView.this.noteBadge(aState, anEmail, aBookName, aBookCode, aChapter, aVerseNo, anExisting);
                }
                @Override public void openAnnotate(final ColState aState, final String aBookName,
                        final String aBookCode, final int aChapter, final String aVerseNo) {
                    // Straight to the unified comment editor — private until published (the
                    // public checkbox is the deliberate act). Notes stay reachable via their
                    // 📝 badges where they exist.
                    ReaderView.this.openCommentEditor(aState, aBookName, aChapter, aVerseNo);
                }
                @Override public String annotateHint() {
                    return ReaderView.this.t("reader.annotate.hint");
                }
                @Override public String lineageWitnessHint() {
                    return ReaderView.this.t("reader.lineage.witness");
                }
                @Override public java.util.Set<String> mutedVoices() {
                    return ReaderView.this.mutedVoices;
                }
            });
        this.scrollController = new ReaderScrollController(new ReaderScrollController.Host() {
            @Override public void loadPrev(final ColState aState) { renderer.loadPrev(aState); }
            @Override public void loadNext(final ColState aState) { renderer.loadNext(aState); }
            @Override public void onVisibleChapterChanged(final ColState aState, final String aBook,
                                                          final int aChapter, final int aSeq) {
                ReaderView.this.onVisibleChapterChanged(aState, aBook, aChapter, aSeq);
            }
        });

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        columnsLayout.getStyle()
            .set("display", "flex")
            .set("flex-direction", "row")
            .set("flex", "1")
            // min-width:0 is essential inside the contentRow flex wrapper: a flex
            // child's default min-width is 'auto', which makes this row GROW to
            // fit every column (pushing the rightmost off-screen, unreachable)
            // instead of overflowing and showing the horizontal scrollbar.
            .set("min-width", "0")
            .set("overflow-x", "auto")
            .set("overflow-y", "hidden")
            .set("min-height", "0");

        // The comments panel sits BESIDE the horizontally-scrolling columns row
        // (a pinned sibling, not a column), hidden until toggled from the
        // toolbar and built lazily on first open.
        commentsPanel.getStyle()
            .set("width", "380px")
            .set("min-width", "300px")
            .set("flex-shrink", "0")
            .set("display", "flex")
            .set("flex-direction", "column")
            .set("min-height", "0")
            .set("border-inline-end", "1px solid var(--lumo-contrast-10pct)");
        commentsPanel.setVisible(false);

        final Div contentRow = new Div(commentsPanel, columnsLayout);
        contentRow.getStyle()
            .set("display", "flex")
            .set("flex-direction", "row")
            .set("flex", "1")
            .set("min-height", "0")
            .set("width", "100%");

        add(buildToolbar(), contentRow);
        setFlexGrow(1, contentRow);
        addColumn();
    }

    // ── Toolbar ──────────────────────────────────────────────────────────────

    /** Translate a key using the session's chosen locale. Uses the session locale
     *  rather than the component's (only correct once attached) so labels built
     *  during construction still localise correctly. */
    private String t(final String aKey) {
        return getTranslation(aKey, LocaleUtil.currentLocale());
    }

    private HorizontalLayout buildToolbar() {
        final HorizontalLayout toolbar = new HorizontalLayout();
        toolbar.setWidthFull();
        toolbar.setAlignItems(Alignment.CENTER);
        toolbar.getStyle()
            .set("padding", "8px 16px")
            .set("background", "var(--lumo-contrast-5pct)")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
            .set("flex-shrink", "0");

        // Brand links home (standard behaviour), carrying the current UI
        // language so the landing page opens in the same language.
        final Anchor title = new Anchor(
            "/?lang=" + LocaleUtil.currentLocale().getLanguage(), "Common Root?");
        title.getStyle().set("font-weight", "bold").set("font-size", "18px")
            .set("color", "inherit").set("text-decoration", "none");

        // Version pill + environment chip — the shared BuildInfo pair every page carries.
        final Div version = buildInfo.badge();

        final Span spacer = new Span();
        spacer.getStyle().set("flex-grow", "1");

        final RouterLink aboutLink = new RouterLink(t("nav.about"), AboutView.class);
        aboutLink.getStyle()
            .set("font-size", "14px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("text-decoration", "none");

        final Anchor sponsorLink = new Anchor(SPONSOR_URL, t("nav.sponsor"));
        sponsorLink.setTarget("_blank");
        sponsorLink.getElement().setAttribute("rel", "noopener");
        sponsorLink.setTitle(t("about.support.title"));
        sponsorLink.getStyle()
            .set("font-size", "14px").set("color", "#c9a84c")
            .set("font-weight", "600").set("text-decoration", "none")
            .set("white-space", "nowrap");

        final Button addBtn = new Button(t("action.addColumn"), VaadinIcon.PLUS.create(), e -> addColumn());
        addBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addBtn.setTooltipText(t("tooltip.addColumn"));

        copyLinkBtn = new Button(t("action.copyLink"), VaadinIcon.LINK.create(), e -> copyCurrentLink());
        copyLinkBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        copyLinkBtn.setTooltipText(t("tooltip.copyLink"));
        // The actual clipboard write happens HERE, client-side, inside the real
        // click gesture — the only thing browsers (esp. Safari on http) reliably
        // allow. The server keeps btn.__crLink updated as the view changes; the
        // server-side click listener only refreshes it and shows the toast.
        copyLinkBtn.getElement().executeJs(
              "const btn = this;"
            + "btn.addEventListener('click', () => {"
            + "  const p = btn.__crLink; if (!p) return;"
            + "  const u = window.location.origin + p;"
            + "  let ok = false;"
            + "  try {"
            + "    const ta = document.createElement('textarea'); ta.value = u;"
            + "    ta.style.position='fixed'; ta.style.top='-1000px'; ta.style.opacity='0';"
            + "    document.body.appendChild(ta); ta.focus({preventScroll:true}); ta.select();"
            + "    ok = document.execCommand('copy'); document.body.removeChild(ta);"
            + "  } catch (e) { ok = false; }"
            + "  if (!ok && navigator.clipboard && navigator.clipboard.writeText) {"
            + "    navigator.clipboard.writeText(u).catch(() => {});"
            + "  }"
            + "});");

        final LanguageSelect langSelect = new LanguageSelect();
        langSelect.setTooltipText(t("tooltip.language"));

        final Button searchBtn = new Button(VaadinIcon.SEARCH.create(), e -> openSearch());
        searchBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        searchBtn.setTooltipText(t("tooltip.search"));

        final Button commentsBtn = new Button(VaadinIcon.COMMENTS.create(), e -> toggleCommentsPanel());
        commentsBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        commentsBtn.setTooltipText(t("reader.comments.title"));
        // A TOGGLE, unlike its one-shot neighbours: tertiary buttons render
        // primary-blue by default, which reads as "panel open" even when it
        // isn't. Muted when closed, primary when open (see updateCommentsBtn).
        this.commentsBtn = commentsBtn;
        updateCommentsBtn();

        // Multi-ref quick-open: type a comma-separated reference list
        // ("John 1:1, Rom 9:5, Col 1:15-17") and press Enter — each token parses
        // to a USFM ref and opens in its own UNSYNCED column via the same
        // openRefInNewColumn path the search picker and comment links use.
        // Unrecognized tokens are skipped; whatever parses still opens.
        final TextField refsField = new TextField();
        refsField.setPlaceholder(t("reader.refs.placeholder"));
        refsField.setTooltipText(t("reader.refs.tooltip"));
        refsField.setClearButtonVisible(true);
        refsField.getStyle().set("min-width", "200px").set("max-width", "340px").set("flex-shrink", "1");
        // Enter opens the list. Read the value straight off the DOM event (no
        // value-sync race) and filter to the Enter key client-side.
        refsField.getElement()
            .addEventListener("keydown",
                e -> openRefList(e.getEventData().getString("event.target.value"), refsField))
            .setFilter("event.key === 'Enter'")
            .addEventData("event.target.value");

        if (authContext.isAuthenticated()) {
            // Display name (fresh from the DB each page load; email prefix fallback) — shared
            // resolution with the About nav via UserService.displayLabel.
            final String label = userService.displayLabel(authContext.getPrincipalName().orElse(""));
            final Anchor profileLink = new Anchor("/profile", label);
            profileLink.getStyle()
                .set("font-size", "14px")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("text-decoration", "none");
            final Button signOut = new Button(t("action.signOut"), e -> authContext.logout());
            signOut.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            toolbar.add(title, version, refsField, spacer, langSelect, aboutLink, sponsorLink, profileLink, signOut, searchBtn, commentsBtn, copyLinkBtn, addBtn);
        } else {
            // A Button (not a static Anchor) so the CURRENT reader state is captured at click
            // time: the live link is stashed in the session and replayed after a successful
            // sign-in (AboutView forwards to it), returning the user to exactly this view.
            final Button signIn = new Button(t("action.signIn"), e -> {
                final var session = com.vaadin.flow.server.VaadinSession.getCurrent().getSession();
                // An empty reader (no configured source column) builds NO link — stash a bare
                // "/reader" so we still return here rather than dumping the user on About.
                final String here = buildCurrentLinkPath();
                session.setAttribute(POST_LOGIN_REDIRECT_ATTR, here != null ? here : "/reader");
                // A VOLUNTARY sign-in must land predictably: drop any stale Spring Security
                // saved request (a protected page bounced days ago would otherwise hijack the
                // post-login redirect and beat the reader stash).
                session.removeAttribute("SPRING_SECURITY_SAVED_REQUEST");
                getUI().ifPresent(ui -> ui.navigate("login"));
            });
            signIn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            signIn.getStyle()
                .set("font-size", "14px")
                .set("font-weight", "400")
                .set("color", "var(--lumo-secondary-text-color)");
            final Anchor createAccount = new Anchor("/register", t("action.createAccount"));
            createAccount.getStyle()
                .set("font-size", "14px")
                .set("background", "#c9a84c")
                .set("color", "#1a3a5c")
                .set("font-weight", "600")
                .set("padding", "6px 14px")
                .set("border-radius", "4px")
                .set("text-decoration", "none")
                .set("white-space", "nowrap");
            toolbar.add(title, version, refsField, spacer, langSelect, aboutLink, sponsorLink, signIn, createAccount, searchBtn, commentsBtn, copyLinkBtn, addBtn);
        }
        return toolbar;
    }

    /** Open the full-text search dialog. Read-only and available to everyone; a
     *  hit routes through openRefInNewColumn — the same new-column + flash
     *  landing the comment panel uses. */
    private void openSearch() {
        new SearchDialog(searchService, (r, sourceId, synced) -> {
            String[] sel = null;
            if (sourceId != null) {
                final String[] byId = sourceById(sourceId);
                // Use the exact edition only when it's a directly-selectable
                // primary source (Bibles + LDS + Qur'an/hadith bases). Qur'an and
                // hadith *translations* aren't primaries (they render beneath
                // their Arabic), so for those fall back to the default base.
                if (byId != null && primarySources().contains(byId)) sel = byId;
            }
            if (sel == null) sel = sourceByToken(r.quran() ? "q-ar" : "niv");
            openRefInNewColumn(r, sel, synced);
        }, id -> {
            final String[] row = sourceById(id);
            return row != null && row.length > 1 ? stripLangGloss(row[1]) : null;
        }, this::t).open();
    }

    /** Toolbar multi-ref open: parse the comma-separated reference list and open
     *  each recognized ref in its own NEW column (unsynced, default edition
     *  NIV / Arabic Qur'an), in input order. Reuses the single-arg
     *  openRefInNewColumn so every column lands + flashes exactly as a comment
     *  link or a single search hit does. Unrecognized tokens are skipped; if the
     *  whole list parses to nothing, a brief notice shows rather than silence. */
    private void openRefList(final String anInput, final TextField aField) {
        if (anInput == null || anInput.isBlank()) return;
        final List<RefListParser.Span> spans =
            RefListParser.parseSpans(anInput, LocaleUtil.currentLocale());
        if (spans.isEmpty()) { Notification.show(t("reader.refs.none")); return; }
        for (final RefListParser.Span sp : spans) openRefInNewColumn(sp.ref(), sp.endVerse());
        // The typed list is deliberately left in the field so it can be re-run,
        // edited, or read back off the screen (demo recording, 2026-08-18).
    }

    // ── Column lifecycle ──────────────────────────────────────────────────────

    private void addColumn() { addColumn(-1); }

    /** Build a column and insert it at wantIndex (append if wantIndex < 0).
     *  Returns the new state so callers (e.g. Quran auto-pairing) can drive it. */
    private ColState addColumn(final int aWantIndex) {
        final SourceColumn col   = new SourceColumn(DisplayOptions.defaults());
        final ColState     state = new ColState();
        state.uid          = ++columnSeq;
        state.col          = col;
        state.bookSelect   = new Select<>();
        state.chapterLabel = new Span("—");
        state.chapterAbbrev = t("reader.chapterAbbrev");
        final int oldSize = columns.size();
        final boolean append = aWantIndex < 0 || aWantIndex > oldSize;
        final int idx = append ? oldSize : aWantIndex;
        columns.add(idx, col);
        states.add(idx, state);

        final Div scrollRoot = new Div();
        scrollRoot.setId("col-" + state.uid);
        scrollRoot.getStyle()
            .set("display", "flex")
            .set("flex-direction", "column")
            .set("width", "33vw")
            .set("min-width", "320px")
            .set("height", "100%")
            .set("overflow-y", "auto")
            .set("overflow-x", "hidden")
            .set("flex-shrink", "0")
            .set("border-right", "1px solid var(--lumo-contrast-10pct)")
            .set("box-sizing", "border-box");
        state.scrollRoot = scrollRoot;

        final Div stickyBar = new Div();
        stickyBar.getStyle()
            .set("position", "sticky")
            .set("top", "0")
            .set("z-index", "10")
            .set("flex-shrink", "0")
            // Keep the browser's scroll-anchoring from latching onto the sticky
            // bar. If it does, removing content above the viewport (a top-trim on
            // forward scroll) fails to compensate scrollTop and the view jumps
            // upward — felt as "scrolling too fast" the moment trimming begins
            // (≈ Job in chronological order). Anchoring to the content instead
            // keeps the reading position fixed across trims.
            .set("overflow-anchor", "none")
            .set("background", "var(--lumo-base-color)");

        final Div header = buildHeader(col, state, scrollRoot);
        final Div nav    = buildNavBar(col, state);
        // Source attribution, kept in the sticky bar so the edition + licence stay
        // visible above the verses no matter how far the column is scrolled.
        final Span attribution = new Span();
        state.attribution = attribution;
        attribution.setVisible(false);
        attribution.getStyle()
            .set("font-size", "11px")
            .set("color", "var(--lumo-tertiary-text-color)")
            .set("padding", "2px 8px 3px")
            .set("border-top", "1px solid var(--lumo-contrast-5pct)")
            .set("line-height", "1.3")
            .set("white-space", "normal");
        stickyBar.add(header, nav, attribution);

        final Div content = new Div();
        content.getStyle()
            .set("padding", "12px 16px")
            .set("flex", "1")
            // Browser scroll-anchoring is DISABLED here on purpose. It was not
            // reliably compensating forward top-trims (the "unhinged at ~Genesis
            // 20 / Job" runaway), so scroll position is now compensated EXPLICITLY
            // in JS for both directions (prepend anchor for backward, captured-
            // anchor restore for forward trims). Leaving it auto risked double-
            // correcting against the manual shifts.
            .set("overflow-anchor", "none")
            .set("box-sizing", "border-box");
        content.add(new Span(t("reader.emptyState")));
        state.content = content;

        scrollRoot.add(stickyBar, content);

        // Register attach listener BEFORE adding to the (attached) layout — for a
        // runtime "Add Column", add() attaches synchronously and the event would
        // fire before a later-registered listener, so the observer would never set
        // up. observerReady makes it idempotent for the first column too.
        scrollRoot.addAttachListener(e -> scrollController.setupObserver(state));
        if (append) columnsLayout.add(scrollRoot);
        else        columnsLayout.getElement().insertChild(idx, scrollRoot.getElement());
        return state;
    }

    private void removeColumn(final ColState aState) {
        final int idx = states.indexOf(aState);
        if (idx >= 0) { columns.remove(idx); states.remove(idx); }
        columnsLayout.remove(aState.scrollRoot);
    }

    /** Set up (or clear) the in-column companion for a newly-selected source.
     *  For a Qur'an source we pair the Arabic base with a translation (and vice
     *  versa) and remember it as this column's companion — shown beneath each
     *  ayah when the translation toggle is on. No second column is opened, so the
     *  pair lives in one scroll container and is always locked. Non-Qur'an
     *  sources get no companion (the toggle stays hidden). Hidden by default on
     *  every fresh source pick. */
    private void setCompanion(final ColState aState, final String[] aSelection) {
        aState.companionId   = null;
        aState.companionRtl  = false;
        aState.showCompanion = false;
        aState.companionText.clear();

        // Translations available for THIS Qur'an, to offer beneath the Arabic.
        final List<String[]> translations = new ArrayList<>();
        if (aSelection != null && aSelection.length >= 9 && hasCompanions(aSelection[6])) {
            final SourceRow selRow = SourceRow.of(aSelection);
            final String baseText = selRow.baseText();
            // The Arabic base this column hangs translations off: the selected
            // source itself when it IS an Arabic base (baseText empty), else the
            // base that the selected translation renders.
            final String baseId = (baseText != null && !baseText.isBlank()) ? baseText : selRow.id();
            translations.addAll(catalog.translationsOf(baseId));   // the translations of this Qur'an
        }

        // Populate the translation picker (Qur'an only). Default companion is the
        // first translation, but it stays HIDDEN until the reader turns it on
        // (eye toggle) or picks one explicitly — the Arabic shows alone first.
        if (aState.translationSelect != null) {
            aState.translationSelect.setVisible(!translations.isEmpty());
            aState.translationSelect.setItems(translations);
            if (!translations.isEmpty()) {
                final String[] first = translations.get(0);
                aState.translationSelect.setValue(first);   // programmatic -> listener ignores
                aState.companionId  = first[0];
                aState.companionRtl = first.length > 3 && "rtl".equalsIgnoreCase(first[3]);
            }
        }

        if (aState.companionToggle != null) {
            aState.companionToggle.setVisible(aState.companionId != null);
            aState.companionToggle.setIcon(VaadinIcon.EYE_SLASH.create());  // reset to "hidden" glyph
            aState.companionToggle.getStyle().set("color", "");   // reset to "off" look
        }

        // Antecedent lineage (Bible editions): the generations of texts this
        // edition stands on, revealed a rung at a time beneath each verse.
        // Reset on every source pick; the rung controls appear only when a
        // lineage is recorded (@basedOn — see patch_lineage.py).
        final String selectedId = aSelection != null && aSelection.length > 0 ? aSelection[0] : null;
        aState.lineage     = catalog.lineageGenerationsOf(selectedId);
        aState.descendants = catalog.descendantGenerationsOf(selectedId);
        aState.rungDepth   = 0;
        aState.rungTexts.clear();
        updateRungButtons(aState);
    }

    /** Verse-granular = the modes that show verses as addressable units
     *  (Verses / Titles) — where lineage rungs render. Null-safe during column
     *  (re)configuration, when the mode can be transiently unset. */
    private static boolean verseGranular(final ColState aState) {
        if (aState.col == null) return false;
        final DisplayOptions o = aState.col.getDisplayOptions();
        return o != null && o.getMode() != null && o.isShowVerses();
    }

    /** Whether a lineage rung has any text for the column's CURRENT book —
     *  the signal the rung buttons use to step over silent rungs (a rung
     *  edition lacking the book, e.g. the Greek NT in Genesis). Unknown book
     *  context reads as "has it" so stepping degrades to plain counting. */
    private boolean rungHasCurrentBook(final org.religioustext.app.ui.views.reader.SourceCatalog.Rung aRung,
                                       final ColState aState) {
        final String code = bookCodeForName(aState, aState.currentBookName());
        if (code == null) return true;
        // Asked of the book, not of its chapter 1 — a rung whose edition prints
        // the book only from chapter 15 onwards still has the book.
        return queryService.hasBookCode(aRung.row()[0], code);
    }

    /**
     * Show/hide + colour the two rung chevrons for the column's position on the
     * signed ladder. ▾ stays available while older generations remain below, ▴
     * while newer ones remain above, and each goes primary-coloured while it is
     * the side currently open — so the colour says which way the column is
     * leaning, not merely that something is showing.
     */
    private void updateRungButtons(final ColState aState) {
        // Rungs are verse-granular; in flow modes (Original/Continuous/
        // Chapters) the controls hide and the depth quietly persists.
        final boolean verseGranular = verseGranular(aState);
        final boolean anyLadder = !aState.lineage.isEmpty() || !aState.descendants.isEmpty();
        if (aState.rungMoreBtn != null) {
            aState.rungMoreBtn.setVisible(verseGranular && anyLadder
                && aState.rungDepth < aState.totalRungs());
            aState.rungMoreBtn.getStyle().set("color",
                aState.rungDepth > 0 ? "var(--lumo-primary-color)" : "");
            // The tooltip has to say which of the two things this click will do,
            // because the glyph cannot: at negative depth ▾ closes a newer
            // translation, otherwise it opens an older one.
            aState.rungMoreBtn.setTooltipText(t(aState.rungDepth < 0
                ? "reader.lineage.closeNewer" : "reader.lineage.more"));
        }
        if (aState.rungLessBtn != null) {
            aState.rungLessBtn.setVisible(verseGranular && anyLadder
                && aState.rungDepth > -aState.totalDescendantRungs());
            aState.rungLessBtn.getStyle().set("color",
                aState.rungDepth < 0 ? "var(--lumo-primary-color)" : "");
            aState.rungLessBtn.setTooltipText(t(aState.rungDepth > 0
                ? "reader.lineage.less" : "reader.lineage.newer"));
        }
    }

    // loadCompanionFor moved to VerseWindowRenderer.

    /** Tailor the display-mode selector to the source type. The Bible-history
     *  framing (Chapters 1227 / Verses 1551 / Scriptio / Titles) does not fit the
     *  Qur'an, whose surah + ayah structure is original to the text; a Qur'an
     *  column gets just Continuous (flowing) and Numbered ayat. visibleSeq is set
     *  to -1 so the mode listener's reload is suppressed during reconfiguration
     *  (the source handler re-opens the window immediately after). */
    private void configureModes(final ColState aState, final String aType) {
        final Select<DisplayOptions.DisplayMode> ms = aState.modeSelect;
        if (ms == null) return;
        aState.visibleSeq = -1;
        final DisplayOptions.DisplayMode cur = ms.getValue();
        // Qur'an + hadith share a trimmed set (Continuous / Numbered): the surah+
        // ayah / numbered-hadith structure is original to the text, so the Bible-
        // history framing (Chapters 1227 / Verses 1551 / Scriptio / Titles) does
        // not apply. Only the "numbered" label differs (ayat vs hadith).
        if (hasCompanions(aType)) {
            final boolean quran = "quran".equals(aType);
            ms.setItemLabelGenerator(m -> m == DisplayOptions.DisplayMode.ORIGINAL_SIMPLE
                ? t("reader.quranMode.continuous")
                : t(quran ? "reader.quranMode.numbered" : "reader.hadithMode.numbered"));
            ms.setItems(DisplayOptions.DisplayMode.ORIGINAL_SIMPLE,
                        DisplayOptions.DisplayMode.CHAPTERS_VERSES);
            ms.setValue((cur == DisplayOptions.DisplayMode.ORIGINAL_SIMPLE
                      || cur == DisplayOptions.DisplayMode.CHAPTERS_VERSES)
                      ? cur : DisplayOptions.DisplayMode.CHAPTERS_VERSES);
        } else {
            ms.setItemLabelGenerator(m -> t("mode." + m.name()));
            ms.setItems(DisplayOptions.DisplayMode.values());
            ms.setValue(cur != null ? cur : DisplayOptions.DisplayMode.CHAPTERS_VERSES);
        }
    }

    // ── Shareable link ──────────────────────────────────────────────
    //
    // On entry, a query string (?cols=&sync=&cN.src/ref/mode/order/companion) is
    // parsed by ReaderLink and the columns are rebuilt to match, each opened at
    // its reference. The toolbar's Copy-link button does the reverse. Refs are
    // edition/language-independent (USFM code / Q.surah.ayah), so a link made in
    // one translation reopens in another. Build implemented for the Bible + Qur'an
    // sources that exist; unknown tokens (comments / hadith / commentary) are
    // skipped until those land.

    @Override
    public void beforeEnter(final BeforeEnterEvent anEvent) {
        loadPrefs();
        final java.util.Map<String, java.util.List<String>> raw =
            anEvent.getLocation().getQueryParameters().getParameters();
        if (raw.isEmpty()) {                             // plain /reader
            if (prefs != null) applyPreferenceDefaults();
            return;                                      // no prefs -> keep the default column
        }
        final java.util.Map<String, String> flat = new java.util.HashMap<>();
        for (final var en : raw.entrySet())
            if (!en.getValue().isEmpty()) flat.put(en.getKey(), en.getValue().get(0));
        // ?comments=<channel> opens the comments panel pre-filtered to that
        // commenter — the per-channel outreach link. Works with or without
        // column specs (alone it opens the panel next to the default column).
        final String commentsChannel = flat.get("comments");
        // ?comment=cmt_… focuses ONE comment: opens the panel filtered to that
        // comment's channel, scrolls its card into view and flashes it — the
        // comment-level counterpart of a verse link's flash landing.
        final String commentId = flat.get("comment");
        final ReaderLink.Parsed parsed = ReaderLink.parse(flat);
        if (!parsed.cols.isEmpty()) applyLink(parsed);
        if (commentId != null && !commentId.isBlank())
            openCommentsPanelForComment(commentId.trim());
        else if (commentsChannel != null) openCommentsPanelFor(channelParam(commentsChannel));
    }

    /** Load the signed-in user's preferences once per view instance. */
    private void loadPrefs() {
        if (prefsLoaded) return;
        prefsLoaded = true;
        if (!authContext.isAuthenticated()) return;
        prefs = prefsService.find(authContext.getPrincipalName().orElse("")).orElse(null);
        mutedVoices = CommentQueryService.parseMuted(prefs == null ? null : prefs.getMutedVoices());
    }

    /** The Bible token substituted for src-less link columns and for opening
     *  Bible comment references, resolved in order: (1) the signed-in user's
     *  saved preference; (2) an edition in the current UI language; (3) NIV. */
    private String defaultBibleToken() {
        if (prefs != null && prefs.getDefaultSource() != null
                && !prefs.getDefaultSource().isBlank())
            return prefs.getDefaultSource();
        final String inUiLanguage =
            bibleTokenForLanguage(LocaleUtil.currentLocale().getLanguage());
        return inUiLanguage != null ? inUiLanguage : "niv";
    }

    /** Curated default edition for UI languages where the corpus offers several
     *  (catalogue order alone would pick by abbreviation: ASV for English,
     *  Biblia 1776 for Finnish, …). Languages absent here fall through to the
     *  catalogue scan in {@link #bibleTokenForLanguage(String)}. */
    private static final java.util.Map<String, String> BIBLE_BY_LANGUAGE =
        java.util.Map.of("en", "niv", "es", "rvr09", "fi", "kr3338", "he", "hebm");

    /**
     * A Bible edition token for a UI language: the curated pick when present in
     * the catalogue, else the first primary Bible whose {@code @lang} matches.
     *
     * @param aLanguage the two-letter UI language (e.g. "fi")
     * @return a source token, or null when no Bible exists in that language
     *         (e.g. French UI — the caller falls back to NIV)
     */
    private String bibleTokenForLanguage(final String aLanguage) {
        if (aLanguage == null || aLanguage.isBlank()) return null;
        final String curated = BIBLE_BY_LANGUAGE.get(aLanguage);
        if (curated != null && catalog.byToken(curated) != null) return curated;
        for (final String[] row : catalog.primaries()) {
            final SourceRow src = SourceRow.of(row);
            if (src.isBible() && aLanguage.equalsIgnoreCase(src.language())
                    && src.abbreviation() != null)
                return src.abbreviation().toLowerCase();
        }
        return null;
    }

    /** A bare /reader for a signed-in user: apply their saved defaults. Resume
     *  takes precedence — the stored position IS a reader-link query string, so
     *  restoring it is just replaying a link (columns, panel, focused comment
     *  and all). Otherwise the fresh default column gets the preferred edition,
     *  mode, order, and marker toggle, and the panel opens if so configured. */
    private void applyPreferenceDefaults() {
        if (prefs.isResumeEnabled() && prefs.getLastPosition() != null
                && !prefs.getLastPosition().isBlank()) {
            final java.util.Map<String, String> flat = new java.util.HashMap<>();
            for (final String kv : prefs.getLastPosition().split("&")) {
                final int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                try {
                    flat.put(java.net.URLDecoder.decode(kv.substring(0, eq),
                                 java.nio.charset.StandardCharsets.UTF_8),
                             java.net.URLDecoder.decode(kv.substring(eq + 1),
                                 java.nio.charset.StandardCharsets.UTF_8));
                } catch (final IllegalArgumentException ignored) { /* malformed pair */ }
            }
            final ReaderLink.Parsed parsed = ReaderLink.parse(flat);
            if (!parsed.cols.isEmpty()) {
                applyLink(parsed);
                final String cid = flat.get("comment");
                if (cid != null && !cid.isBlank()) openCommentsPanelForComment(cid.trim());
                else if (flat.get("comments") != null) openCommentsPanelFor(channelParam(flat.get("comments")));
                else if (prefs.isShowCommentsPanel()) openCommentsPanelFor(null);
                return;
            }
        }
        // No resume position — dress the fresh default column in the defaults.
        // Order/mode BEFORE the source pick, so the open uses them (the source
        // listener reloads books in the active order; configureModes keeps a
        // current value that fits the source type).
        final ColState st = states.isEmpty() ? null : states.get(0);
        if (st != null) {
            if (prefs.getDefaultOrder() != null)
                st.orderSelect.setValue(ReaderLink.orderFromToken(prefs.getDefaultOrder()));
            final DisplayOptions.DisplayMode m = ReaderLink.modeFromToken(prefs.getDefaultMode());
            if (m != null) st.modeSelect.setValue(m);
            st.showComments = prefs.isShowCommentMarkers();
            if (st.commentsToggle != null) {
                st.commentsToggle.setIcon(
                    (st.showComments ? VaadinIcon.COMMENT : VaadinIcon.COMMENT_O).create());
                st.commentsToggle.getStyle().set("color",
                    st.showComments ? "var(--lumo-primary-color)" : "");
            }
            final String[] sel = prefs.getDefaultSource() == null ? null
                : sourceByToken(prefs.getDefaultSource());
            if (sel != null) selectSource(st, sel);      // opens at the start
        }
        if (prefs.isShowCommentsPanel()) openCommentsPanelFor(null);
    }

    /** Persist the current position (the Copy-link query string) for "continue
     *  where I left off". Rate-limited; a no-op unless the user's preferences
     *  row exists with resume enabled (the service guards the write too). */
    private void maybeSavePosition() {
        if (prefs == null || !prefs.isResumeEnabled()) return;
        final long now = System.currentTimeMillis();
        if (now - lastPositionSaveMs < 5000) return;
        final String path = buildCurrentLinkPath();
        if (path == null) return;
        lastPositionSaveMs = now;
        final int q = path.indexOf('?');
        prefsService.savePosition(authContext.getPrincipalName().orElse(""),
            q >= 0 ? path.substring(q + 1) : path);
    }

    /** Rebuild the columns from a parsed link and open each at its reference. */
    private void applyLink(final ReaderLink.Parsed aParsed) {
        for (final ColState s : new ArrayList<>(states)) removeColumn(s);
        currentSeq = -1; currentBook = null; currentChapter = 1;

        for (final ReaderLink.ColSpec spec : aParsed.cols) {
            final ColState st  = addColumn(-1);
            // The named edition, else the user's preferred Bible, else NIV — so
            // src-less links (?c1.ref=JHN.3) and unknown tokens still open a
            // readable column instead of an empty one.
            String[] sel = spec.src == null ? null : sourceByToken(spec.src);
            if (sel == null) sel = sourceByToken(defaultBibleToken());
            if (sel == null) sel = sourceByToken("niv");
            if (sel == null) continue;                   // no fallback exists -> empty column

            // Order first, so the book list and seq lookups use it.
            st.orderSelect.setValue(spec.order != null ? spec.order : OrderMode.CANONICAL);
            // Set up without sync so the source pick opens at start, not a shared pos.
            if (st.col.isSynced()) st.col.toggleSync();
            // Selecting the source loads books + companion + modes and opens at start.
            selectSource(st, sel);

            final boolean trimmed = sel.length > 6 && hasCompanions(sel[6]);
            if (spec.mode != null) {
                DisplayOptions.DisplayMode m = spec.mode;
                if (trimmed && m != DisplayOptions.DisplayMode.ORIGINAL_SIMPLE
                            && m != DisplayOptions.DisplayMode.CHAPTERS_VERSES)
                    m = DisplayOptions.DisplayMode.CHAPTERS_VERSES;   // clamp to the trimmed set
                st.modeSelect.setValue(m);
            }
            if (spec.companion > 0 && st.companionId != null) {
                st.showCompanion = true;
                st.companionToggle.setIcon(VaadinIcon.EYE.create());
                st.companionToggle.getStyle().set("color", "var(--lumo-primary-color)");
                refreshBookNames(st);
                updateAttribution(st);
            }
            // Bible columns have no companionId; there the token names how many
            // lineage generations open beneath each verse (clamped to what the
            // edition actually has).
            if (spec.companion > 0 && st.companionId == null && !st.lineage.isEmpty()) {
                st.rungDepth = Math.min(spec.companion, st.totalRungs());
                updateRungButtons(st);
                updateAttribution(st);
            }

            // Restore this column's in-text comment markers before the open so
            // they render on first paint (opt-in, off by default, so a link that
            // omits the flag correctly leaves the text pristine).
            st.showComments = spec.comments;
            if (st.commentsToggle != null) {
                st.commentsToggle.setIcon(
                    (spec.comments ? VaadinIcon.COMMENT : VaadinIcon.COMMENT_O).create());
                st.commentsToggle.getStyle().set("color",
                    spec.comments ? "var(--lumo-primary-color)" : "");
            }

            final int seq = resolveRefSeq(st, spec.ref);
            if (seq >= 0) {
                // A verse-precision ref (cN.ref=BOOK.CH.VS) gets the same flash
                // landing a comment ref does, and is remembered so Copy-link
                // round-trips at verse precision. Memory BEFORE openAtSeq — it
                // refreshes the copy link internally (see openRefInNewColumn).
                if (spec.ref != null && spec.ref.b() > 0) {
                    final String code = spec.ref.quran()
                        ? String.valueOf(spec.ref.a()) : spec.ref.unit();
                    final int chap = spec.ref.quran() ? 1 : spec.ref.a();
                    st.openedRefBook    = bookNameForCode(st, code);
                    st.openedRefChapter = chap;
                    st.openedRefVerse   = spec.ref.b();
                    openAtSeq(st, seq);
                    // A ranged ref highlights the whole passage (a Qur'an range
                    // must stay within one surah — the id scheme is per-surah).
                    if (spec.ref.hasEnd() && !(spec.ref.quran() && spec.ref.endA() != spec.ref.a()))
                        flashRange(st, code, chap, spec.ref.b(),
                                   spec.ref.quran() ? 1 : spec.ref.endA(), spec.ref.endB());
                    else
                        flashVerse(st, "v-" + code + "-" + chap + "-" + spec.ref.b());
                } else {
                    openAtSeq(st, seq);
                }
            } else openAtStart(st);

            // Extra spans named by cN.hl — painted but NOT scrolled to, because
            // the anchor decides where the reader lands. In a reordered edition
            // the passage worth pointing at is the one that MOVED, which is
            // usually below the landing point, so it lights up as the reader
            // scrolls into it rather than stealing the opening view.
            flashSpans(st, spec.highlights);

            setColumnSync(st, aParsed.sync);              // restore requested sync flag
        }
        seedSharedPosition();
    }

    /** Find the source row whose @abbreviation (lowercased) matches the token. */
    private String[] sourceByToken(final String aToken) {
        return catalog.byToken(aToken);
    }

    /** English book @name for a USFM code (Bible) or surah number (Qur'an), from
     *  the column's loaded book list. */
    private String bookNameForCode(final ColState aState, final String aCode) {
        if (aCode == null) return null;
        for (final String[] b : aState.books)
            if (b.length > 3 && aCode.equalsIgnoreCase(b[3])) return b[0];
        return null;
    }

    /** Inverse of bookNameForCode: USFM code / surah number for an English name. */
    private String bookCodeForName(final ColState aState, final String aBookName) {
        if (aBookName == null) return null;
        for (final String[] b : aState.books)
            if (b.length > 3 && aBookName.equals(b[0])) return b[3];
        return null;
    }

    /** Resolve a link reference to an active-order seq for this column, or -1. */
    private int resolveRefSeq(final ColState aState, final ReaderLink.Ref aRef) {
        if (aRef == null || aState.col.getSourceId() == null) return -1;
        final String code = aRef.quran() ? String.valueOf(aRef.a()) : aRef.unit();
        final String book = bookNameForCode(aState, code);
        if (book == null) return -1;
        final int chapter = aRef.quran() ? 1 : aRef.a();
        final int verse   = aRef.b();
        return queryService.seqForRef(aState.col.getSourceId(), aState.order(), book, chapter, verse);
    }

    /** Force a column's sync flag to the desired value and update its icon. */
    private void setColumnSync(final ColState aState, final boolean aSyncedFlag) {
        if (aState.col.isSynced() != aSyncedFlag) aState.col.toggleSync();
        if (aState.syncBtn != null)
            aState.syncBtn.setIcon(aSyncedFlag ? VaadinIcon.LINK.create() : VaadinIcon.UNLINK.create());
    }

    /** Seed the shared sync position from the first synced, sourced column so the
     *  next sync action has a reference point. */
    private void seedSharedPosition() {
        for (final ColState s : states) {
            if (s.col.isSynced() && s.col.getSourceId() != null) {
                currentBook    = s.currentBookName();
                currentChapter = s.visibleChapter;
                currentSeq     = (currentBook != null)
                    ? queryService.seqForBookChapter(s.col.getSourceId(), s.order(), currentBook, currentChapter)
                    : -1;
                return;
            }
        }
    }

    /** Build a shareable link for the current columns and copy it to the clipboard.
     *  Refs are chapter-precision (book.chapter) from the live view; verse-
     *  precision is honoured on the open side for hand-authored / comment links. */
    /** Build the "/reader?..." path for the current columns, or null if none are
     *  configured. Feeds both the client-side copy handler and the empty-check. */
    private String buildCurrentLinkPath() {
        final List<ReaderLink.ColSpec> specs = new ArrayList<>();
        boolean allSynced = true;
        for (final ColState s : states) {
            if (s.col.getSourceId() == null) continue;   // skip unconfigured columns
            final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
            c.src       = s.col.getAbbreviation() == null ? null : s.col.getAbbreviation().toLowerCase();
            c.order     = s.order();
            c.mode      = s.col.getDisplayOptions().getMode();
            // Ancestor depth only. The link grammar's companion=N is >= 1, so a
            // NEGATIVE (descendant) depth has no representation and is dropped
            // rather than silently encoded as its opposite — a shared link that
            // opened the wrong half of the ladder would be worse than one that
            // opens none of it. Widening the grammar is a separate decision.
            c.companion = s.showCompanion ? 1 : Math.max(0, s.rungDepth);
            c.comments  = s.showComments;
            final String code = bookCodeForName(s, s.currentBookName());
            if (code != null) {
                // Verse precision when this column was opened at a specific verse
                // and still shows that (book, chapter); otherwise chapter-only.
                int verse = 0;
                if (s.openedRefVerse > 0
                        && s.currentBookName() != null
                        && s.currentBookName().equals(s.openedRefBook)
                        && s.visibleChapter == s.openedRefChapter)
                    verse = s.openedRefVerse;
                c.ref = isQuranSource(s.col.getSourceId())
                    ? new ReaderLink.Ref(true, "Q", parseIntOr(code, 0), verse)
                    : new ReaderLink.Ref(false, code, s.visibleChapter, verse);
            }
            specs.add(c);
            if (!s.col.isSynced()) allSynced = false;
        }
        if (specs.isEmpty()) return null;
        String path = "/reader?" + ReaderLink.build(specs, allSynced)
            + "&lang=" + org.religioustext.app.i18n.LocaleUtil.currentLocale().getLanguage();
        // URL reflects the comments panel too: visible + filtered -> carry the
        // channel, so copy-link produces per-channel outreach links directly.
        // Visible but UNFILTERED carries the sentinel COMMENTS_ALL, so "panel open, no
        // commenter chosen" round-trips as well — otherwise an open, empty panel simply
        // vanished from the link (and from the post-login return).
        if (commentsPanel.isVisible()) {
            final String channel = commentsChannelFilter == null ? null : commentsChannelFilter.getValue();
            path += "&comments=" + (channel == null || channel.isBlank()
                ? COMMENTS_ALL
                : java.net.URLEncoder.encode(channel, java.nio.charset.StandardCharsets.UTF_8));
        }
        // … and the focused comment: the copied link replays the flash landing
        // alongside whatever columns its refs opened (comment= wins over
        // comments= on the open side and presets the same channel filter anyway).
        if (commentsPanel.isVisible() && focusedCommentId != null && !focusedCommentId.isBlank()) {
            path += "&comment=" + java.net.URLEncoder.encode(
                focusedCommentId, java.nio.charset.StandardCharsets.UTF_8);
        }
        return path;
    }

    /** Push the current link path onto the Copy-link button so its client-side
     *  click handler copies it within the user gesture. Called whenever the view
     *  changes (openAtSeq funnels navigation; the scroll handler covers in-place
     *  chapter changes). Also mirrors the path into the browser address bar via
     *  history.replaceState, so the URL always reflects what is currently open
     *  and can be bookmarked/shared as-is (no history entries are added — the
     *  back button is unaffected). */
    private void refreshCopyLink() {
        if (copyLinkBtn == null) return;
        final String path = buildCurrentLinkPath();
        copyLinkBtn.getElement().executeJs(
            "this.__crLink = $0;"
            + " if ($0 && location.pathname.startsWith('/reader'))"
            + " history.replaceState(null, '', $0);",
            path == null ? "" : path);
        maybeSavePosition();
    }

    /** Toolbar click. The clipboard write itself happens client-side in the
     *  button's own click handler (wired in buildToolbar), inside the user
     *  gesture; here we just keep the target fresh and show feedback. */
    private void copyCurrentLink() {
        final String path = buildCurrentLinkPath();
        refreshCopyLink();
        Notification.show(path == null ? t("reader.link.empty") : t("reader.link.copied"));
    }

    private boolean isQuranSource(final String aSourceId) {
        return catalog.isQuran(aSourceId);
    }

    /** Full source row for an id, or null. */
    private String[] sourceById(final String anId) {
        return catalog.byId(anId);
    }

    /** Refresh the per-column attribution line: the primary text's edition name +
     *  licence, plus the companion translation's when one is shown. The @source
     *  (origin / URL) rides along as the hover title. Shown wherever verses are. */
    private void updateAttribution(final ColState aState) {
        if (aState.attribution == null) return;
        final StringBuilder label = new StringBuilder();
        final StringBuilder tip   = new StringBuilder();
        if (aState.srcTranslation != null && !aState.srcTranslation.isBlank()) {
            label.append(aState.srcTranslation);
            if (aState.srcLicense != null && !aState.srcLicense.isBlank())
                label.append(" \u2014 ").append(aState.srcLicense);
            if (aState.srcSource != null && !aState.srcSource.isBlank())
                tip.append(aState.srcTranslation).append(": ").append(aState.srcSource);
        }
        if (aState.showCompanion && aState.companionId != null) {
            final String[] c = sourceById(aState.companionId);
            if (c != null) {
                final String cn = c.length > 1 && c[1] != null && !c[1].isBlank() ? c[1] : c[0];
                label.append("   +   ").append(cn);
                if (c.length > 4 && c[4] != null && !c[4].isBlank())
                    label.append(" \u2014 ").append(c[4]);
                if (c.length > 5 && c[5] != null && !c[5].isBlank()) {
                    if (tip.length() > 0) tip.append("\n");
                    tip.append(cn).append(": ").append(c[5]);
                }
            }
        }
        for (final org.religioustext.app.ui.views.reader.SourceCatalog.Rung rungInfo
                : verseGranular(aState)
                    ? aState.activeRungs()
                    : java.util.List.<org.religioustext.app.ui.views.reader.SourceCatalog.Rung>of()) {
            final String[] rung = rungInfo.row();
            final String rn = rung.length > 1 && rung[1] != null && !rung[1].isBlank() ? rung[1] : rung[0];
            label.append("   +   ");
            if (!rungInfo.attested()) label.append("\u2248 ");   // witness, not the attested parent
            label.append(rn);
            if (rung.length > 4 && rung[4] != null && !rung[4].isBlank())
                label.append(" \u2014 ").append(rung[4]);
            if (rung.length > 5 && rung[5] != null && !rung[5].isBlank()) {
                if (tip.length() > 0) tip.append("\n");
                tip.append(rn).append(": ").append(rung[5]);
            }
        }
        aState.attribution.setText(label.toString());
        aState.attribution.setVisible(label.length() > 0);
        if (tip.length() > 0) aState.attribution.getElement().setAttribute("title", tip.toString());
        else                  aState.attribution.getElement().removeAttribute("title");
    }

    private static int parseIntOr(final String aString, final int aFallback) {
        try { return Integer.parseInt(aString.trim()); } catch (final Exception e) { return aFallback; }
    }

    /** Primary (directly selectable) sources: Bibles + the Qur'an base(s) + hadith,
     *  excluding Qur'an translations (those attach beneath the Arabic). */
    private List<String[]> primarySources() {
        return catalog.primaries();
    }

    /** Distinct flag emoji for the languages of the companion translations attached
     *  to the given base source (Qur'an today), space-joined; "" when the text has
     *  no translations. Drives the small translation hints in the source picker. */
    private String translationFlags(final String aBaseId) {
        final java.util.LinkedHashSet<String> flags = new java.util.LinkedHashSet<>();
        for (final String[] r : catalog.translationsOf(aBaseId)) {
            final String fl = LANG_FLAGS.get(SourceRow.of(r).language().toLowerCase());
            if (fl != null) flags.add(fl);
        }
        return String.join(" ", flags);
    }

    /** Programmatically select a source, setting its language tier first so the
     *  scoped tier-2 picker contains it (used by the shareable-link restore). */
    private void selectSource(final ColState aState, final String[] aSelection) {
        if (aSelection == null) return;
        final String lang = aSelection.length > 7 ? aSelection[7] : "";
        if (!java.util.Objects.equals(lang, aState.langCombo.getValue()))
            aState.langCombo.setValue(lang);
        aState.sourceCombo.setValue(aSelection);
    }

    // ── Column header ────────────────────────────────────────────────────────

    private Div buildHeader(final SourceColumn aColumn, final ColState aState, final Div aScrollRoot) {
        final Div header = new Div();
        header.getStyle()
            .set("display", "flex")
            .set("align-items", "center")
            // Wrap, so on a narrow column the controls flow onto another line
            // instead of overflowing the (clipped) column edge and hiding the
            // trailing buttons. The 4px gap doubles as the row gap when wrapped.
            .set("flex-wrap", "wrap")
            .set("gap", "4px")
            .set("padding", "6px 8px")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        // Tier 1 — language. Choosing a language scopes the source list beneath it;
        // the flag here makes the language redundant in tier 2, so tier-2 labels drop
        // the flag and any trailing "(Language)" gloss. The UI language is offered first.
        final String uiLang = org.religioustext.app.i18n.LocaleUtil.currentLocale().getLanguage();
        final List<String[]> primary = primarySources();
        final List<String> langs = primary.stream()
            .map(r -> r.length > 7 ? r[7] : "")
            .filter(l -> !l.isBlank()).distinct()
            .sorted(java.util.Comparator
                .<String>comparingInt(l -> l.equals(uiLang) ? 0 : 1)
                .thenComparing(java.util.Comparator.naturalOrder()))
            .toList();
        final Select<String> langCombo = new Select<>();
        aState.langCombo = langCombo;
        langCombo.setTooltipText(t("tooltip.source"));
        langCombo.setItemLabelGenerator(ReaderView::langLabel);
        langCombo.setItems(langs);
        langCombo.getStyle().set("min-width", "104px").set("flex-shrink", "0");

        // Tier 2 — texts in the chosen language (Bibles, then the Qur'an, then hadith,
        // then by abbreviation). Disabled until a language is picked.
        final ComboBox<String[]> sourceCombo = new ComboBox<>();
        aState.sourceCombo = sourceCombo;
        sourceCombo.setPlaceholder(t("reader.sourcePlaceholder"));
        sourceCombo.setTooltipText(t("tooltip.source"));
        sourceCombo.setItemLabelGenerator(arr -> arr[2] + " \u2014 " + stripLangGloss(arr.length > 1 ? arr[1] : ""));
        // Dropdown rows additionally carry small flag(s) for any companion-
        // translation languages the text offers (Qur'an only today), so the reader
        // can tell at a glance which texts have a translation. The closed field keeps
        // the plain label (above).
        sourceCombo.setRenderer(new com.vaadin.flow.data.renderer.ComponentRenderer<>(arr -> {
            final Span lbl = new Span(arr[2] + " \u2014 " + stripLangGloss(arr.length > 1 ? arr[1] : ""));
            final String flags = translationFlags(arr[0]);
            if (flags.isEmpty()) return lbl;
            final Span tr = new Span(flags);
            tr.getStyle().set("font-size", "0.7em").set("margin-inline-start", "6px")
              .set("opacity", "0.75").set("white-space", "nowrap");
            final Span row = new Span(lbl, tr);
            row.getStyle().set("display", "inline-flex").set("align-items", "baseline")
               .set("flex-wrap", "wrap").set("gap", "2px");
            return row;
        }));
        sourceCombo.setEnabled(false);
        langCombo.addValueChangeListener(e -> {
            final String lang = e.getValue();
            sourceCombo.clear();
            sourceCombo.setEnabled(lang != null);
            sourceCombo.setItems(lang == null ? List.<String[]>of() : primary.stream()
                .filter(r -> lang.equals(r.length > 7 ? r[7] : ""))
                .sorted(java.util.Comparator
                    .comparingInt((String[] r) -> typeRank(r.length > 6 ? r[6] : ""))
                    .thenComparing(r -> r.length > 2 ? r[2] : ""))
                .toList());
        });
        // A Qur'an *translation* (type quran with a baseText) is not a standalone
        // primary text — it is shown beneath the Arabic via the translation picker.
        // Offer only Bibles + the Arabic Qur'an base(s) here.
        sourceCombo.getStyle().set("flex", "1").set("min-width", "120px");
        // Default to the UI language so the reader's own texts are ready to pick.
        if (langs.contains(uiLang)) langCombo.setValue(uiLang);
        sourceCombo.addValueChangeListener(e -> {
            final String[] sel = e.getValue();
            if (sel == null) return;
            aColumn.setSource(sel[0], sel[1], sel[2], sel[3],
                sel.length > 4 ? sel[4] : null,
                sel.length > 5 ? sel[5] : null);
            aState.srcTranslation = sel.length > 1 ? sel[1] : sel[2];
            aState.srcLicense     = sel.length > 4 ? sel[4] : null;
            aState.srcSource      = sel.length > 5 ? sel[5] : null;
            // RTL applies to the verse CONTENT only — keep the header/nav controls
            // LTR so the toolbar doesn't reverse and clip the eye / sync / remove
            // buttons off the column's (left) edge.
            if (aColumn.isRtl()) aState.content.getStyle().set("direction", "rtl");
            else                 aState.content.getStyle().remove("direction");
            loadBookList(aState);
            if (aState.books.isEmpty()) return;
            setCompanion(aState, sel);
            configureModes(aState, sel.length > 6 ? sel[6] : "");
            updateAttribution(aState);
            // Open at the shared location if synced and one exists, else the start.
            if (aColumn.isSynced() && currentSeq >= 0 && currentBook != null) {
                final int seq = queryService.seqForBookChapter(
                    aColumn.getSourceId(), aState.order(), currentBook, currentChapter);
                if (seq >= 0) { openAtSeq(aState, seq); return; }
            }
            openAtStart(aState);
        });

        final Select<DisplayOptions.DisplayMode> modeSelect = new Select<>();
        modeSelect.setItems(DisplayOptions.DisplayMode.values());
        modeSelect.setValue(DisplayOptions.DisplayMode.CHAPTERS_VERSES);
        modeSelect.setItemLabelGenerator(m -> t("mode." + m.name()));
        aState.modeSelect = modeSelect;
        modeSelect.setTooltipText(t("tooltip.mode"));
        modeSelect.getStyle().set("min-width", "155px").set("flex-shrink", "0");
        modeSelect.addValueChangeListener(e -> {
            // configureModes' setItems() clears the value TRANSIENTLY (the
            // listener fires with null before the real value lands) — bail,
            // or anything downstream that asks the mode a question NPEs and
            // aborts the whole column setup.
            if (e.getValue() == null) return;
            aColumn.getDisplayOptions().setMode(e.getValue());
            updateRungButtons(aState);   // rung controls exist only in verse-granular modes
            updateAttribution(aState);
            if (aColumn.getSourceId() != null && aState.visibleSeq >= 0) reloadAtVisible(aState);
        });

        final Select<OrderMode> orderSelect = new Select<>();
        orderSelect.setItems(OrderMode.values());
        orderSelect.setValue(OrderMode.CANONICAL);
        orderSelect.setItemLabelGenerator(m -> t("order." + m.name()));
        aState.orderSelect = orderSelect;
        orderSelect.setTooltipText(t("tooltip.order"));
        orderSelect.getStyle().set("min-width", "110px").set("flex-shrink", "0");
        orderSelect.addValueChangeListener(e -> {
            // Remember the visible (book, chapter) so we can re-anchor in the new
            // order — the seq value itself changes meaning between orders.
            final String book = aState.currentBookName();
            final int    chap = aState.visibleChapter;
            aColumn.getDisplayOptions().setOrderMode(e.getValue());
            if (aColumn.getSourceId() == null) return;
            loadBookList(aState);
            if (aState.books.isEmpty()) return;
            final int seq = (book != null)
                ? queryService.seqForBookChapter(aColumn.getSourceId(), aState.order(), book, chap)
                : -1;
            if (seq >= 0) openAtSeq(aState, seq);
            else          openAtStart(aState);
        });

        final Select<String[]> translationSelect = new Select<>();
        aState.translationSelect = translationSelect;
        translationSelect.setVisible(false);
        translationSelect.setItemLabelGenerator(r -> r.length > 1 ? r[1] : r[0]);
        translationSelect.setTooltipText(t("tooltip.translation"));
        translationSelect.getStyle().set("min-width", "150px").set("flex-shrink", "0");
        translationSelect.addValueChangeListener(e -> {
            if (!e.isFromClient()) return;   // ignore the programmatic default set in setCompanion
            final String[] tr = e.getValue();
            if (tr == null) return;
            aState.companionId  = tr[0];
            aState.companionRtl = tr.length > 3 && "rtl".equalsIgnoreCase(tr[3]);
            // Choosing a translation reveals it beneath the Arabic (the paired view).
            aState.showCompanion = true;
            if (aState.companionToggle != null) {
                aState.companionToggle.setIcon(VaadinIcon.EYE.create());
                aState.companionToggle.getStyle().set("color", "var(--lumo-primary-color)");
            }
            refreshBookNames(aState);
            updateAttribution(aState);
            if (aState.col.getSourceId() != null && aState.visibleSeq >= 0) reloadAtVisible(aState);
        });

        final Button companionToggle = new Button(VaadinIcon.EYE_SLASH.create());
        companionToggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        companionToggle.setVisible(false);
        companionToggle.setTooltipText("Show / hide translation");
        aState.companionToggle = companionToggle;
        companionToggle.addClickListener(e -> {
            aState.showCompanion = !aState.showCompanion;
            // Swap the glyph itself (EYE = shown, EYE_SLASH = hidden) so the state
            // is visible at a glance; colour alone was indistinguishable.
            companionToggle.setIcon((aState.showCompanion ? VaadinIcon.EYE : VaadinIcon.EYE_SLASH).create());
            companionToggle.getStyle().set("color",
                aState.showCompanion ? "var(--lumo-primary-color)" : "");
            refreshBookNames(aState);
            updateAttribution(aState);
            if (aState.col.getSourceId() != null && aState.visibleSeq >= 0) reloadAtVisible(aState);
        });

        // Antecedent-lineage rungs (Bible editions): the down-rung reveals one
        // more generation of the texts this edition stands on beneath each
        // verse; the up-rung hides the deepest. Hidden entirely for editions
        // with no recorded lineage (and for Qur'an/hadith columns, which use
        // the companion toggle above instead).
        final Button rungLess = new Button(VaadinIcon.ANGLE_UP.create());
        rungLess.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        rungLess.setVisible(false);
        rungLess.setTooltipText(t("reader.lineage.less"));
        aState.rungLessBtn = rungLess;
        rungLess.addClickListener(e -> {
            // ▴ moves one step UP the signed ladder, which means two things in
            // sequence and feels like one: while ancestors are open it shuts the
            // deepest (plus any SILENT rungs left on top, so a click always
            // removes a visible line); once they are all shut it carries on in
            // the same direction and opens the translations based on this one.
            int d = aState.rungDepth;
            if (d > 0) {
                d--;
                final java.util.List<org.religioustext.app.ui.views.reader.SourceCatalog.Rung> ladder = aState.allRungs();
                while (d > 0 && !rungHasCurrentBook(ladder.get(d - 1), aState)) d--;
            } else {
                final java.util.List<org.religioustext.app.ui.views.reader.SourceCatalog.Rung> ladder = aState.allDescendantRungs();
                int n = -d;
                boolean found = false;
                while (n < ladder.size()) {
                    n++;
                    if (rungHasCurrentBook(ladder.get(n - 1), aState)) { found = true; break; }
                }
                if (found) d = -n;
            }
            aState.rungDepth = d;
            updateRungButtons(aState);
            updateAttribution(aState);
            if (aState.col.getSourceId() != null && aState.visibleSeq >= 0) reloadAtVisible(aState);
        });
        final Button rungMore = new Button(VaadinIcon.ANGLE_DOWN.create());
        rungMore.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        rungMore.setVisible(false);
        rungMore.setTooltipText(t("reader.lineage.more"));
        aState.rungMoreBtn = rungMore;
        rungMore.addClickListener(e -> {
            // ▾ is the same axis walked the other way: it shuts any open
            // descendants first, then advances to the NEXT ancestor rung that
            // actually has text in the current book — silent rungs (TR in the
            // OT, WLC in the NT) are stepped over so a click always changes a
            // visible line. If nothing further has text, stay put.
            int d = aState.rungDepth;
            if (d < 0) {
                final java.util.List<org.religioustext.app.ui.views.reader.SourceCatalog.Rung> ladder = aState.allDescendantRungs();
                int n = -d - 1;
                while (n > 0 && !rungHasCurrentBook(ladder.get(n - 1), aState)) n--;
                d = -n;
            } else {
                final java.util.List<org.religioustext.app.ui.views.reader.SourceCatalog.Rung> ladder = aState.allRungs();
                boolean found = false;
                while (d < ladder.size()) {
                    d++;
                    if (rungHasCurrentBook(ladder.get(d - 1), aState)) { found = true; break; }
                }
                if (!found) d = aState.rungDepth;
            }
            aState.rungDepth = d;
            updateRungButtons(aState);
            updateAttribution(aState);
            if (aState.col.getSourceId() != null && aState.visibleSeq >= 0) reloadAtVisible(aState);
        });

        // In-text comment markers are OPT-IN per column: some readers consider
        // anything inserted into the scripture text itself inappropriate, so the
        // text renders untouched until this is switched on. The comments panel
        // and dialogs remain fully usable either way.
        final Button commentsToggle = new Button(VaadinIcon.COMMENT_O.create());
        aState.commentsToggle = commentsToggle;
        commentsToggle.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        commentsToggle.setTooltipText(t("reader.comments.toggleMarkers"));
        commentsToggle.addClickListener(e -> {
            aState.showComments = !aState.showComments;
            commentsToggle.setIcon((aState.showComments ? VaadinIcon.COMMENT : VaadinIcon.COMMENT_O).create());
            commentsToggle.getStyle().set("color",
                aState.showComments ? "var(--lumo-primary-color)" : "");
            if (aState.col.getSourceId() != null && aState.visibleSeq >= 0) reloadAtVisible(aState);
        });

        final Button syncBtn = new Button(VaadinIcon.LINK.create());
        syncBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        syncBtn.setTooltipText(t("tooltip.sync"));
        aState.syncBtn = syncBtn;
        syncBtn.addClickListener(e -> {
            aColumn.toggleSync();
            syncBtn.setIcon(aColumn.isSynced() ? VaadinIcon.LINK.create() : VaadinIcon.UNLINK.create());
            if (aColumn.isSynced() && aColumn.getSourceId() != null && currentBook != null) {
                final int seq = queryService.seqForBookChapter(
                    aColumn.getSourceId(), aState.order(), currentBook, currentChapter);
                if (seq >= 0) openAtSeq(aState, seq);
            }
        });

        final Button removeBtn = new Button(VaadinIcon.CLOSE_SMALL.create());
        removeBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_ERROR);
        removeBtn.setTooltipText(t("tooltip.removeColumn"));
        removeBtn.addClickListener(e -> removeColumn(aState));

        header.add(langCombo, sourceCombo, modeSelect, orderSelect, translationSelect, companionToggle, rungMore, rungLess, commentsToggle, syncBtn, removeBtn);
        return header;
    }

    // ── Nav bar ──────────────────────────────────────────────────────────────

    private Div buildNavBar(final SourceColumn aColumn, final ColState aState) {
        final Div nav = new Div();
        nav.getStyle()
            .set("display", "flex")
            .set("align-items", "center")
            .set("gap", "4px")
            .set("padding", "4px 8px")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        aState.bookSelect.setPlaceholder(t("reader.bookPlaceholder"));
        aState.bookSelect.setTooltipText(t("tooltip.book"));
        aState.bookSelect.getStyle().set("flex", "1").set("min-width", "0").set("max-width", "300px");
        aState.bookSelect.addValueChangeListener(e -> {
            if (e.getValue() == null || aColumn.getSourceId() == null) return;
            if (!e.isFromClient()) return;   // ignore programmatic updates
            final String bookName = e.getValue();
            // Where the book STARTS here, which is not always chapter 1: Agricola's
            // Exodus begins at 15. Asking for chapter 1 returned seq -1 and the
            // listener returned silently, so choosing a book did nothing at all.
            final Integer firstChap = aState.firstChapterForBook(aState.indexOfBook(bookName));
            if (firstChap == null) return;
            final int seq = queryService.seqForBookChapter(
                aColumn.getSourceId(), aState.order(), bookName, firstChap);
            if (seq < 0) return;
            openAtSeq(aState, seq);
            if (aColumn.isSynced()) { currentSeq = seq; currentBook = bookName; currentChapter = firstChap; syncOthers(aState); }
        });

        aState.chapterLabel.getStyle()
            .set("min-width", "52px")
            .set("text-align", "center")
            .set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("flex-shrink", "0");

        final Button prev = new Button(VaadinIcon.ANGLE_LEFT.create(), e -> stepChapter(aState, -1));
        final Button next = new Button(VaadinIcon.ANGLE_RIGHT.create(), e -> stepChapter(aState, +1));
        prev.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        next.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        prev.setTooltipText(t("tooltip.prevChapter"));
        next.setTooltipText(t("tooltip.nextChapter"));

        // The label is also the jump box. A click swaps it for a small field;
        // Enter (or blurring away) commits. Kept as a swap rather than a
        // permanent input so the nav bar still reads as a label at rest —
        // a text field sitting between two arrows looks like a form to fill in.
        final TextField chapterInput = new TextField();
        aState.chapterInput = chapterInput;
        chapterInput.setVisible(false);
        chapterInput.setWidth("62px");
        chapterInput.getStyle().set("flex-shrink", "0");
        chapterInput.getElement().setAttribute("inputmode", "numeric");
        chapterInput.setTooltipText(t("tooltip.chapterJump"));

        aState.chapterLabel.getStyle().set("cursor", "pointer");
        aState.chapterLabel.getElement().setAttribute("title", t("tooltip.chapterJump"));
        aState.chapterLabel.addClickListener(e -> {
            if (aState.col.getSourceId() == null) return;
            chapterInput.setValue("");
            aState.chapterLabel.setVisible(false);
            chapterInput.setVisible(true);
            chapterInput.focus();
        });

        final Runnable commit = () -> {
            // Enter fires, then the field loses focus and blur fires too. The
            // visibility flag is the guard: one gesture, one jump.
            if (!chapterInput.isVisible()) return;
            final String raw = chapterInput.getValue();
            chapterInput.setVisible(false);
            aState.chapterLabel.setVisible(true);
            if (raw == null || raw.isBlank()) return;
            final int wanted;
            try { wanted = Integer.parseInt(raw.trim()); }
            catch (final NumberFormatException ex) { return; }
            jumpToChapter(aState, wanted);
        };
        chapterInput.addKeyPressListener(Key.ENTER, e -> commit.run());
        chapterInput.addBlurListener(e -> commit.run());

        nav.add(aState.bookSelect, prev, aState.chapterLabel, chapterInput, next);
        return nav;
    }

    /**
     * Go to a typed chapter of the book already open.
     *
     * <p>Checked against the chapters the book ACTUALLY has here rather than
     * against a count: Agricola's Exodus is 15, 19, 20 and 32, so a reader who
     * types 1 must be told the edition does not print it instead of watching
     * nothing happen — which is exactly how the book dropdown failed before
     * 0.7.0. Everything after the check is the prev/next path: scroll if the
     * chapter is already in the DOM window, otherwise re-open around it.
     *
     * @param aState   the column
     * @param aChapter the chapter number the reader typed
     */
    private void jumpToChapter(final ColState aState, final int aChapter) {
        if (aState.col.getSourceId() == null) return;
        final String book = aState.currentBookName();
        if (book == null) return;
        boolean present = false;
        for (final int c : aState.chapterNumbersForBook(aState.bookIndex))
            if (c == aChapter) { present = true; break; }
        if (!present) { Notification.show(t("reader.chapter.absent")); return; }

        final int seq = queryService.seqForBookChapter(
            aState.col.getSourceId(), aState.order(), book, aChapter);
        if (seq < 0) { Notification.show(t("reader.chapter.absent")); return; }

        if (seq >= aState.firstSeq && seq <= aState.lastSeq) {
            scrollController.scrollToSeqAnchor(aState, book, aChapter, true);
            aState.setVisible(aChapter, book);
        } else {
            openAtSeq(aState, seq);
        }
        if (aState.col.isSynced()) {
            currentSeq = seq; currentBook = book; currentChapter = aChapter;
            syncOthers(aState);
        }
    }

    /** Prev/next chapter button. Resolves the target (book, chapter) in the
     *  ACTIVE order, then either scrolls to it if it's in the DOM window or
     *  re-opens around it. Crossing a book boundary moves to the adjacent book
     *  in the active-order book list. */
    private void stepChapter(final ColState aState, final int aDelta) {
        if (aState.col.getSourceId() == null || aState.books.isEmpty()) return;
        String  targetBook = aState.currentBookName();
        Integer targetChap = aState.chapterStep(aState.bookIndex, aState.visibleChapter, aDelta);

        if (targetChap == null) {          // off the end of this book, either way
            final int neighbour = aState.bookIndex + (aDelta < 0 ? -1 : 1);
            if (neighbour < 0 || neighbour >= aState.books.size()) return;
            targetBook = aState.books.get(neighbour)[0];
            targetChap = aDelta < 0 ? aState.lastChapterForBook(neighbour)
                                    : aState.firstChapterForBook(neighbour);
            if (targetChap == null) return;
        }
        final int seq = queryService.seqForBookChapter(
            aState.col.getSourceId(), aState.order(), targetBook, targetChap);
        if (seq < 0) return;

        if (seq >= aState.firstSeq && seq <= aState.lastSeq) {
            scrollController.scrollToSeqAnchor(aState, targetBook, targetChap, true);
            aState.setVisible(targetChap, targetBook);
        } else {
            openAtSeq(aState, seq);
        }
        if (aState.col.isSynced()) {
            currentSeq = seq; currentBook = targetBook; currentChapter = targetChap;
            syncOthers(aState);
        }
    }

    // ── Book list (active order) ───────────────────────────────────────────────

    private void loadBookList(final ColState aState) {
        final List<String[]> books = queryService.listBooksWithChapterCounts(
            aState.col.getSourceId(), aState.order());
        aState.books = books;
        // Build the display-name map. Priority: a localised name for the book's
        // standard code in the SOURCE'S OWN language (a Swedish Bible shows
        // Swedish book names regardless of UI locale — book names belong to the
        // text, like the edition name; Christa 2026-07-04); else the Arabic
        // surah name for a right-to-left edition (the Arabic Qur'an); else the
        // English name. Languages without a booknames bundle fall back to the
        // English base — NoFallbackControl prevents the JVM default locale from
        // leaking in. data-book / sync keys stay the English name (field [0]) so
        // cross-edition alignment is unaffected.
        aState.bookDisplay.clear();
        final boolean rtl = aState.col.isRtl();
        java.util.ResourceBundle names = null;
        try {
            final String[] srcRow = sourceById(aState.col.getSourceId());
            final java.util.Locale srcLoc = srcRow == null ? null
                : LocaleUtil.fromTag(SourceRow.of(srcRow).language());
            names = java.util.ResourceBundle.getBundle("i18n/booknames",
                srcLoc == null ? java.util.Locale.ROOT : srcLoc,
                java.util.ResourceBundle.Control.getNoFallbackControl(
                    java.util.ResourceBundle.Control.FORMAT_DEFAULT));
        }
        catch (final Exception ignored) { /* no bundle -> fall back below */ }
        for (final String[] b : books) {
            String shown = null;
            final String code = b.length > 3 ? b[3] : null;
            if (names != null && code != null && !code.isBlank()) {
                try { shown = names.getString(code); }
                catch (final java.util.MissingResourceException ignored) { /* e.g. Qur'an surah codes */ }
            }
            if (shown == null)
                shown = (rtl && b.length > 2 && b[2] != null && !b[2].isBlank()) ? b[2] : b[0];
            aState.bookDisplay.put(b[0], shown);
        }
        final List<String> bookKeys = books.stream().map(b -> b[0]).toList();
        aState.bookSelect.setItems(bookKeys);
        // Localised label in the picker; the English value stays the selection
        // (the query/sync key).
        aState.bookSelect.setTextRenderer(n -> bookHeading(aState, n));
    }

    /** Book label for the active source. When a translation is shown beneath an
     *  Arabic (RTL) text, append the transliterated/Latin name after the Arabic
     *  one (e.g. Arabic surah name + " \u2014 Al-Baqarah"); otherwise just the
     *  localized name. bookName is the canonical (Latin) @name; its bookDisplay is
     *  the Arabic surah name for the RTL Qur'an. */
    private String bookHeading(final ColState aState, final String aBookName) {
        final String shown = aState.bookDisplay.getOrDefault(aBookName, aBookName);
        if (aState.showCompanion && aBookName != null && !aBookName.equals(shown))
            return shown + " \u2014 " + aBookName;
        return shown;
    }

    /** Re-render the book dropdown labels: they gain/lose the appended Latin name
     *  as the companion translation is shown/hidden. Re-setting the text renderer
     *  forces the Select to relabel every item (refreshAll alone may not). */
    private void refreshBookNames(final ColState aState) {
        if (aState.bookSelect != null)
            aState.bookSelect.setTextRenderer(n -> bookHeading(aState, n));
    }

    // ── Window open / reload ────────────────────────────────────────────────────

    /** Open the column at the very start of the active order. */
    private void openAtStart(final ColState aState) {
        final int seq = queryService.minSeq(aState.col.getSourceId(), aState.order());
        openAtSeq(aState, seq);
    }

    /** Re-open the window around the currently-visible seq (used when display
     *  mode changes — same position, re-rendered). */
    private void reloadAtVisible(final ColState aState) {
        final int anchor = aState.visibleSeq >= 0 ? aState.visibleSeq : aState.firstSeq;
        if (anchor < 0) { openAtStart(aState); return; }
        openAtSeq(aState, anchor);
    }

    /**
     * THE core entry point. Clears the column and renders a window centred on
     * anchorSeq: roughly half the window of context before it and half after,
     * so the anchor lands a little below the top with room to scroll up. Scrolls
     * the anchored verse to the top. All navigation (source pick, book dropdown,
     * order change, prev/next, sync) funnels through here.
     */
    private void openAtSeq(final ColState aState, final int anAnchorSeq) {
        if (aState.col.getSourceId() == null) return;
        applyContainerStyle(aState);

        final DisplayOptions opts = aState.col.getDisplayOptions();
        // Half the window before the anchor, the rest from the anchor on.
        final int before = WINDOW_VERSES / 3;          // ~1 viewport of lead-in above
        final List<VerseRef> head = (anAnchorSeq > 0)
            ? queryService.verseWindowBefore(aState.col.getSourceId(), opts, anAnchorSeq, before)
            : new ArrayList<>();
        final List<VerseRef> tail = queryService.verseWindowFrom(
            aState.col.getSourceId(), opts, anAnchorSeq, WINDOW_VERSES - head.size());

        final List<VerseRef> window = new ArrayList<>(head);
        window.addAll(tail);
        if (window.isEmpty()) return;

        aState.scrollRoot.getElement().setAttribute("data-jumping", "true");
        renderer.clearContent(aState);
        aState.companionText.clear();
        aState.rungTexts.clear();
        renderer.loadCompanionFor(aState, window);
        renderer.renderWindow(aState, window);

        // Bounds + visible position.
        aState.firstSeq   = activeSeq(aState, window.get(0));
        aState.lastSeq    = activeSeq(aState, window.get(window.size() - 1));
        // The anchor verse is the first one at/after anAnchorSeq → that's where the
        // viewport should sit. Find it for the label.
        VerseRef anchorVerse = tail.isEmpty() ? window.get(0) : tail.get(0);
        aState.visibleSeq   = activeSeq(aState, anchorVerse);
        aState.visibleChapter = anchorVerse.getChapterNumber();
        aState.setVisible(anchorVerse.getChapterNumber(), anchorVerse.getBookName());

        // Scroll the anchored (book, chapter) to the top after the DOM settles.
        // NOTE: element-scoped executeJs, NOT ui.getPage() — during beforeEnter
        // (deep-link entry) the view is NOT ATTACHED yet, so getUI() is empty
        // and a Page.executeJs is silently SKIPPED: the scroll never ran and
        // the window sat at the lead-in top (the "deep link opens a chapter
        // early" bug — root cause found 2026-07-07; the session-31 race theory
        // patched a branch that never executed on this path). An Element
        // invocation is queued while detached and runs on attach.
        final String book  = anchorVerse.getBookName();
        final int    chap  = anchorVerse.getChapterNumber();
        aState.scrollRoot.getElement().executeJs("""
            const root = this;
            if (root._jumpToAnchor) { root._jumpToAnchor($0, $1); return; }
            // Observer not wired yet (fresh deep-link column): scroll to the
            // anchor group directly once the subtree is in the DOM. Offset by
            // the sticky bar's height — a bare scrollIntoView(block:'start')
            // aligns the anchor with the container top, where the sticky
            // header/nav/attribution OVERLAYS it (the "lands a couple of
            // verses in" symptom).
            setTimeout(function() {
                const el = root.querySelector(
                    '[data-book="' + $0 + '"][data-chapter="' + $1 + '"]');
                if (el) {
                    const bar = root.firstElementChild;
                    const off = bar ? bar.offsetHeight : 0;
                    root.scrollTop += el.getBoundingClientRect().top
                                    - root.getBoundingClientRect().top - off;
                } else root.scrollTop = 0;
                root.removeAttribute('data-jumping');
            }, 40);
        """, book, String.valueOf(chap));
        refreshCopyLink();
    }

    private void applyContainerStyle(final ColState aState) {
        final boolean caps = aState.col.getDisplayOptions().isAllCaps();
        aState.content.getStyle()
            .set("font-family",    caps ? "monospace" : "inherit")
            .set("white-space",    caps ? "pre-wrap"  : "normal")
            .set("word-break",     caps ? "break-all" : "normal")
            .set("overflow-wrap",  caps ? "break-word": "normal")
            .set("max-width",      caps ? "100%"      : "none")
            .set("line-height",    caps ? "1.8"       : "inherit");
    }

    private int activeSeq(final ColState aState, final VerseRef aVerseRef) {
        final Integer s = switch (aState.order()) {
            case CHRONOLOGICAL -> aVerseRef.getGlobalChronologicalSeq();
            default            -> aVerseRef.getGlobalCanonicalSeq();
        };
        return s != null ? s : (aVerseRef.getGlobalCanonicalSeq() != null ? aVerseRef.getGlobalCanonicalSeq() : 0);
    }

    // ── Window rendering + fill ───────────────────────────────────────────────
    // renderWindow / buildChapterGroup / appendNormalVerses / buildBookSeparator /
    // mergeIntoLastGroup + the DOM append/prepend/clear helpers moved to
    // VerseWindowRenderer. openAtSeq drives it; the scroll observer fires loads.

    // ── Comments under verses ────────────────────────────────────────────────
    //
    // Verses cited by public comments (the transcript-derived arguments seeded
    // under the Platform user, and any future user comments) get a small 💬
    // badge. Clicking it opens the comments: the argument text, links to every
    // verse it cites, and the source video — playable in a popup at the moment
    // the verse is discussed (the enrichment timecode), with a plain YouTube
    // link as fallback (embedding can be disabled per channel). A cited verse
    // always opens in a NEW, unsynced column appended to the layout — never by
    // rebuilding the reader — so the current columns are left undisturbed.

    /** Superscript badge marking a verse that has comments. */
    private Span commentBadge(final ColState aState, final String aBookName, final int aChapter,
                              final String aVerseNo, final List<VerseComment> theComments) {
        final Span b = new Span("\uD83D\uDCAC" + (theComments.size() > 1 ? theComments.size() : ""));
        b.getStyle()
            .set("cursor", "pointer")
            .set("font-size", "11px")
            .set("font-weight", "600")
            .set("vertical-align", "super")
            .set("user-select", "none")
            .set("direction", "ltr")
            .set("display", "inline-block")
            // Filled primary pill — a community comment is the whole point of the
            // contribution layer, so the marker is meant to be seen, not whispered.
            .set("color", "var(--lumo-primary-contrast-color)")
            .set("background", "var(--lumo-primary-color)")
            .set("border", "1px solid var(--lumo-primary-color)")
            .set("border-radius", "10px")
            .set("padding", "0 6px")
            .set("line-height", "1.5")
            .set("margin", "0 3px");
        b.getElement().setAttribute("title", t("reader.comments.title"));
        b.addClickListener(e -> openCommentsDialog(aState, aBookName, aChapter, aVerseNo, theComments));
        return b;
    }

    private void openCommentsDialog(final ColState aState, final String aBookName, final int aChapter,
                                    final String aVerseNo, final List<VerseComment> theComments) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(bookHeading(aState, aBookName) + " " + aChapter + ":" + aVerseNo
            + " — " + t("reader.comments.title"));
        dialog.setWidth("600px");
        dialog.setMaxWidth("94vw");
        dialog.setMaxHeight("82vh");
        dialog.setDraggable(true);   // drag by the header title bar
        dialog.setResizable(true);   // resize from the edges

        final java.util.Map<String, String> levels = effectiveLevelsFor(theComments);
        for (final VerseComment c : theComments)
            dialog.add(commentCard(c, dialog::close, levels.get(c.publicId())));

        // Signed-in readers can add their own comment to this verse from here
        // (the note editor offers the same for verses with no comments yet).
        if (authContext.isAuthenticated()) {
            final Button write = new Button(t("reader.commentForm.write"),
                VaadinIcon.PENCIL.create(), e -> {
                    dialog.close();
                    openCommentEditor(aState, aBookName, aChapter, aVerseNo);
                });
            write.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            dialog.getFooter().add(write);
        }
        dialog.open();
    }

    // ── Writing / editing one's own comment ────────────────────────────
    //
    // The public counterpart of the private note editor, with the same one-per-
    // verse mental model: the dialog binds the caller's own comment on the verse
    // (prefilled when it exists → Save updates, Delete removes; empty → Save
    // creates). Anchoring is edition-independent (bookCode, chapter, verse);
    // the open edition rides along as provenance. A verse-only comment auto-
    // approves on publish — the moderation queue exists for external links,
    // which this form deliberately doesn't take.

    private void openCommentEditor(final ColState aState, final String aBookName,
                                   final int aChapter, final String aVerseNo) {
        final int verse;
        try { verse = Integer.parseInt(aVerseNo); }
        catch (final NumberFormatException ex) { return; }   // non-numeric ref — no anchor
        final String bookCode = bookCodeForName(aState, aBookName);
        if (bookCode == null) return;
        final String email = authContext.getPrincipalName().orElse("");
        if (email.isBlank()) return;

        final List<CommentAuthorService.OwnComment> mine =
            commentAuthor.forVerse(email, bookCode, aChapter, verse);
        final CommentAuthorService.OwnComment existing = mine.isEmpty() ? null : mine.get(0);

        final String title = bookHeading(aState, aBookName) + " " + aChapter + ":" + aVerseNo
            + " — " + t(existing == null ? "reader.commentForm.titleNew" : "reader.commentForm.titleEdit");
        // PRIVATE-FIRST: a new comment starts unpublished — making it public is the
        // deliberate act (and requires the contributor role at save).
        final com.vaadin.flow.component.checkbox.Checkbox publicBox =
            new com.vaadin.flow.component.checkbox.Checkbox(t("reader.commentForm.public"),
                existing != null && existing.isPublic());
        publicBox.setTooltipText(t("reader.commentForm.public.helper"));
        // A rejected comment shows its reason, so the author knows what to fix.
        final String rejection = existing != null && "rejected".equals(existing.moderationStatus())
            ? t("reader.commentForm.rejected")
                + (existing.rejectionReason() == null ? "" : " — " + existing.rejectionReason())
            : null;

        if (existing == null) {
            // COMPOSE: the verse being commented on is the fixed anchor chip; extra passages
            // and the link are held in memory and persisted right after create (a new user
            // comment's public id IS its row id, so the follow-up calls resolve it).
            final MemRefs mem = new MemRefs(new VerseComment.Ref(
                isQuranSource(aState.col.getSourceId()), bookCode, aChapter, verse));
            openUnifiedCommentDialog(title, "", mem, publicBox, null, null,
                text -> {
                    final String id = commentAuthor.create(email, aState.col.getSourceId(), bookCode,
                        aChapter, verse, text, Boolean.TRUE.equals(publicBox.getValue()));
                    if (!mem.extras().isEmpty()) commentAuthor.addRefs(email, id, mem.extras());
                    if (mem.extUrl() != null) commentAuthor.setExternalLink(email, id, mem.extUrl(), mem.extLabel());
                },
                () -> afterOwnCommentChange(aState));
        } else {
            openUnifiedCommentDialog(title, existing.content(),
                persistedRefs(email, existing.publicId()), publicBox, rejection,
                () -> commentAuthor.delete(email, existing.id()),
                text -> commentAuthor.update(email, existing.id(), text,
                    Boolean.TRUE.equals(publicBox.getValue())),
                () -> afterOwnCommentChange(aState));
        }
    }

    /** After creating / editing / deleting one's own comment: re-render the
     *  active column (the 💬 badge appears/updates — forChapter queries live)
     *  and rebuild the comments panel so its cached list reflects the change. */
    private void afterOwnCommentChange(final ColState aState) {
        final boolean panelWasVisible = commentsPanel.isVisible();
        // Preserve the user's channel filter across the rebuild — losing it also meant
        // re-rendering EVERY comment (the post-save freeze).
        final String keepChannel = commentsChannelFilter != null ? commentsChannelFilter.getValue() : null;
        commentsPanel.removeAll();
        commentsPanelBuilt = false;
        commentsChannelFilter = null;
        commentsAll = null;
        if (panelWasVisible) {
            buildCommentsPanel(keepChannel);
            commentsPanelBuilt = true;
            commentsPanel.setVisible(true);
        }
        if (aState != null && aState.col.getSourceId() != null && aState.visibleSeq >= 0)
            reloadAtVisible(aState);
    }

    // -- Personal notes under verses ------------------------------------------
    //
    // The private half of a signed-in account: a per-verse note only its author
    // can see. Rendered only for authenticated readers, never for guests. The
    // badge is faint until a note exists, filled once it does; clicking opens an
    // inline editor. Notes are edition-independent, so the same note shows on the
    // verse in every translation.

    /** Superscript note marker for one verse. Faint when empty, filled when a
     *  private note exists. */
    private Span noteBadge(final ColState aState, final String anEmail, final String aBookName,
                           final String aBookCode, final int aChapter,
                           final String aVerseNo, final String anExisting) {
        final Span b = new Span("\uD83D\uDCDD");   // memo
        b.getStyle()
            .set("cursor", "pointer")
            .set("font-size", "10px")
            .set("vertical-align", "super")
            .set("user-select", "none")
            .set("direction", "ltr")
            .set("display", "inline-block")
            .set("margin", "0 2px");
        final boolean has = anExisting != null && !anExisting.isBlank();
        b.getStyle().set("opacity", has ? "1" : "0.3");
        b.getElement().setAttribute("title", t(has ? "reader.notes.edit" : "reader.notes.add"));
        b.addClickListener(e -> openNoteEditor(b, aState, anEmail, aBookName, aBookCode, aChapter, aVerseNo));
        return b;
    }

    /** Private per-verse note editor. The badge is passed in so its filled/faint
     *  state updates immediately on save or delete, with no column re-render. */
    private void openNoteEditor(final Span aBadge, final ColState aState, final String anEmail,
                                final String aBookName, final String aBookCode,
                                final int aChapter, final String aVerseNo) {
        final int verse;
        try { verse = Integer.parseInt(aVerseNo); }
        catch (final NumberFormatException ex) { return; }   // non-numeric ref — no note target

        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(bookHeading(aState, aBookName) + " " + aChapter + ":" + aVerseNo
            + " \u2014 " + t("reader.notes.title"));
        dialog.setWidth("560px");
        dialog.setMaxWidth("94vw");

        final TextArea ta = new TextArea();
        ta.setWidthFull();
        ta.setMinHeight("160px");
        ta.setPlaceholder(t("reader.notes.placeholder"));
        noteService.find(anEmail, aBookCode, aChapter, verse).ifPresent(ta::setValue);
        ta.focus();

        final Button save = new Button(t("action.save"), e -> {
            noteService.save(anEmail, aState.col.getSourceId(), aBookCode, aChapter, verse, ta.getValue());
            final boolean nowHas = ta.getValue() != null && !ta.getValue().isBlank();
            if (aBadge != null) {   // null when opened from a bare verse's number (no badge yet)
                aBadge.getStyle().set("opacity", nowHas ? "1" : "0.3");
                aBadge.getElement().setAttribute("title", t(nowHas ? "reader.notes.edit" : "reader.notes.add"));
            }
            Notification.show(t("reader.notes.saved"), 2000, Notification.Position.BOTTOM_START);
            dialog.close();
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Button cancel = new Button(t("action.cancel"), e -> dialog.close());
        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        final Button delete = new Button(t("action.delete"), e -> {
            noteService.delete(anEmail, aBookCode, aChapter, verse);
            if (aBadge != null) {
                aBadge.getStyle().set("opacity", "0.3");
                aBadge.getElement().setAttribute("title", t("reader.notes.add"));
            }
            Notification.show(t("reader.notes.deleted"), 2000, Notification.Position.BOTTOM_START);
            dialog.close();
        });
        delete.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);

        // The public counterpart lives one step away: a verse with no 💬 badge
        // has no other entry point for WRITING a comment, so the always-present
        // note editor offers the jump.
        final Button publicComment = new Button(t("reader.commentForm.fromNotes"), e -> {
            dialog.close();
            openCommentEditor(aState, aBookName, aChapter, aVerseNo);
        });
        publicComment.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        dialog.add(ta);
        dialog.getFooter().add(publicComment, delete, cancel, save);
        dialog.open();
    }

    /** Toolbar 💬: toggle the comment-driven browser. Built lazily on first
     *  open (the seed only changes at startup, so no rebuild per toggle). */
    private void toggleCommentsPanel() {
        if (!commentsPanelBuilt) {
            buildCommentsPanel();
            commentsPanelBuilt = true;
        }
        commentsPanel.setVisible(!commentsPanel.isVisible());
        if (!commentsPanel.isVisible()) focusedCommentId = null;   // stale focus won't resurface on reopen
        updateCommentsBtn();
        refreshCopyLink();
    }

    /** Deep-link entry ({@code ?comments=<channel>}): open the comments panel with the
     *  commenter filter preset. A blank/unknown channel opens it unfiltered. */
    private void openCommentsPanelFor(final String aChannel) {
        if (!commentsPanelBuilt) {
            buildCommentsPanel(aChannel);   // first render already filtered — no all-cards pass
            commentsPanelBuilt = true;
        } else if (aChannel != null && !aChannel.isBlank() && commentsChannelFilter != null) {
            commentsChannelFilter.setValue(aChannel);   // fires the filter listener
        }
        commentsPanel.setVisible(true);
        updateCommentsBtn();
    }

    /** Deep-link entry (?comment=cmt_…): open the comments panel focused on ONE
     *  comment — filter preset to its channel (so the list is short), the card
     *  scrolled into view and flashed with the same highlight a linked verse
     *  gets. An unknown/stale id opens the panel unfiltered with a notice, so
     *  the link still lands somewhere useful. */
    private void openCommentsPanelForComment(final String aPublicId) {
        if (!commentsPanelBuilt) {
            buildCommentsPanel();
            commentsPanelBuilt = true;
        }
        commentsPanel.setVisible(true);
        updateCommentsBtn();
        VerseComment target = null;
        if (commentsAll != null) {
            for (final VerseComment c : commentsAll) {
                if (aPublicId.equals(c.publicId())) { target = c; break; }
            }
        }
        if (target == null) {
            Notification.show(t("reader.comments.linkNotFound"));
            return;
        }
        focusedCommentId = aPublicId;
        if (commentsChannelFilter != null)
            commentsChannelFilter.setValue(channelOf(target));   // re-renders the list
        flashComment(aPublicId);
        refreshCopyLink();
    }

    /** Scroll the comment card [data-cid=publicId] into view and flash it — the
     *  panel-side twin of {@link #flashVerse}. The interval re-queries every tick
     *  because the filter preset re-renders the list right after the deep link
     *  opens it (replacing the card node); for the first ~2.5s the card is kept
     *  centred, after that the reader's own scrolling is left alone while the
     *  highlight fades out. */
    private void flashComment(final String aPublicId) {
        final int holdMs = 6000;
        commentsPanel.getElement().executeJs(
              "const sel = '[data-cid=' + JSON.stringify($0) + ']';"
            + "const until = Date.now() + $1;"
            + "const settle = Date.now() + 2500;"
            + "const paint = (el, on) => {"
            + "  if (!el) return;"
            + "  el.style.transition = 'background-color .4s';"
            + "  el.style.backgroundColor = on ? 'rgba(255,235,59,.45)' : '';"
            + "};"
            + "const timer = setInterval(() => {"
            + "  const el = this.querySelector(sel);"
            + "  const done = Date.now() >= until;"
            + "  if (el) {"
            + "    if (Date.now() < settle)"
            + "      el.scrollIntoView({block:'center', inline:'nearest'});"
            + "    paint(el, !done);"
            + "  }"
            + "  if (done) clearInterval(timer);"
            + "}, 250);", aPublicId, holdMs);
    }

    private void buildCommentsPanel() { buildCommentsPanel(null); }

    /** Build the comments panel, optionally already filtered to one channel — a rebuild that
     *  passes the previous filter renders ONLY that channel's cards instead of all ~3.6k
     *  (the all-cards render was the post-save freeze) and keeps the user's selection. */
    private void buildCommentsPanel(final String anInitialChannel) {
        final Span title = new Span(t("reader.comments.title"));
        title.getStyle().set("font-weight", "600");
        final Button close = new Button(VaadinIcon.CLOSE_SMALL.create(),
            e -> { commentsPanel.setVisible(false); focusedCommentId = null;
                   updateCommentsBtn(); refreshCopyLink(); });
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        final Div head = new Div(title, close);
        head.getStyle()
            .set("display", "flex")
            .set("justify-content", "space-between")
            .set("align-items", "center")
            .set("padding", "8px 12px")
            .set("flex-shrink", "0");

        final Div list = new Div();
        list.getStyle()
            .set("overflow-y", "auto")
            .set("flex", "1")
            .set("min-height", "0")
            .set("padding", "0 12px 12px");

        final List<VerseComment> all = commentService.listAll();
        commentsAll = all;

        // Searchable commenter filter — type to narrow, clear to show all.
        final ComboBox<String> channelFilter = new ComboBox<>();
        commentsChannelFilter = channelFilter;
        channelFilter.setPlaceholder(t("reader.comments.filterChannel"));
        channelFilter.setClearButtonVisible(true);
        channelFilter.setWidthFull();
        channelFilter.setItems(all.stream().map(ReaderView::channelOf)
            .filter(s -> s != null && !s.isBlank())
            // A muted voice is not on offer here either — except the one a deep
            // link named, which the reader asked for explicitly.
            .filter(v -> !mutedVoices.contains(v) || v.equals(anInitialChannel))
            .distinct().sorted().toList());
        final Div filterWrap = new Div(channelFilter);
        filterWrap.getStyle()
            .set("padding", "0 12px 8px")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
            .set("flex-shrink", "0");
        channelFilter.addValueChangeListener(e -> renderCommentList(list, all, e.getValue()));

        if (anInitialChannel != null && !anInitialChannel.isBlank()) {
            channelFilter.setValue(anInitialChannel);   // fires the listener → filtered render
        } else {
            renderCommentList(list, all, null);
        }
        commentsPanel.add(head, filterWrap, list);
    }

    private void renderCommentList(final Div aList, final List<VerseComment> theComments, final String aChannel) {
        aList.removeAll();
        final List<VerseComment> matching = new ArrayList<>();
        final boolean unfiltered = aChannel == null || aChannel.isBlank();
        for (final VerseComment c : theComments) {
            if (!unfiltered && !aChannel.equals(channelOf(c))) continue;
            // Muted voices stay hidden in the panel as well as in the text, so the
            // two surfaces never disagree about what exists. The exception is a
            // voice the reader explicitly selected (only reachable via a
            // ?comments=<channel> deep link once muted): a deliberate act outranks
            // a standing preference, and without it a channel's own outreach link
            // would open an empty reader.
            final boolean asked = !unfiltered && aChannel.equals(channelOf(c));
            if (!asked && CommentQueryService.isMuted(c, mutedVoices)) continue;
            matching.add(c);
        }

        if (matching.isEmpty()) {
            final Span empty = new Span(t("reader.comments.empty"));
            empty.getStyle()
                .set("color", "var(--lumo-secondary-text-color)")
                .set("display", "block")
                .set("padding", "12px 0");
            aList.add(empty);
            return;
        }
        appendCommentPage(aList, matching, unfiltered, 0);
    }

    /** Append one page of comment cards starting at {@code aFrom}, followed by the
     *  "Show more" footer when any remain. APPENDS rather than re-renders: rebuilding the
     *  whole list threw the scroll position back to the top on every click. A filtered view
     *  has no cap — it renders in one page. */
    private void appendCommentPage(final Div aList, final List<VerseComment> theMatching,
                                   final boolean anUnfiltered, final int aFrom) {
        final int to = anUnfiltered
            ? Math.min(theMatching.size(), aFrom + UNFILTERED_CARD_CAP)
            : theMatching.size();
        final List<VerseComment> page = theMatching.subList(aFrom, to);
        // One bulk permission lookup for exactly this page's cards.
        final java.util.Map<String, String> levels = effectiveLevelsFor(page);
        for (final VerseComment c : page)
            aList.add(commentCard(c, null, levels.get(c.publicId())));

        if (to >= theMatching.size()) return;

        // The cap keeps the panel instant, but it must not be a dead end: one more page per
        // click, alongside the filter hint. The footer is replaced in place by the next page.
        final Div footer = new Div();
        footer.getStyle().set("padding", "12px 0 6px");
        final Span capped = new Span(getTranslation("reader.comments.capped",
            org.religioustext.app.i18n.LocaleUtil.currentLocale(), to, theMatching.size()));
        capped.getStyle()
            .set("color", "var(--lumo-secondary-text-color)")
            .set("font-size", "13px")
            .set("display", "block")
            .set("padding-bottom", "6px");
        final Button more = new Button(t("reader.comments.showMore"), e -> {
            aList.remove(footer);   // drop the footer, append the next page after the cards
            appendCommentPage(aList, theMatching, anUnfiltered, to);
        });
        more.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        footer.add(capped, more);
        aList.add(footer);
    }

    /** A {@code ?comments=} value as a channel filter: the {@link #COMMENTS_ALL} sentinel
     *  (blank, or the legacy {@code *} from links already in the wild) means "open the
     *  panel, no filter" — i.e. null. */
    static String channelParam(final String aValue) {
        return aValue == null || aValue.isBlank()
            || COMMENTS_ALL.equals(aValue) || "*".equals(aValue) ? null : aValue;
    }

    /** The caller's effective level per comment (BasicLevel name keyed by public id) — ONE
     *  bulk lookup for a rendered list, empty for guests. Drives the ✎ affordance. */
    private java.util.Map<String, String> effectiveLevelsFor(final List<VerseComment> theComments) {
        if (!authContext.isAuthenticated() || theComments == null || theComments.isEmpty())
            return java.util.Map.of();
        final List<String> ids = new ArrayList<>();
        for (final VerseComment c : theComments)
            if (c.publicId() != null && !c.publicId().isBlank()) ids.add(c.publicId());
        if (ids.isEmpty()) return java.util.Map.of();
        return commentAuthor.editableLevels(authContext.getPrincipalName().orElse(""), ids);
    }

    /** Commenter (the source channel) of a comment: the external ref's label when
     *  present, else parsed from the seeded "[Channel — Tradition — type]" header. */
    /** Add a voice to this reader's mute list and drop it out of the view.
     *
     *  <p>Persisted immediately rather than on a Save button: this is a single
     *  deliberate click on a named voice, and asking someone to confirm that they
     *  meant it would be worse than the mistake. Preferences is where it comes
     *  back off. */
    private void muteVoice(final String aVoice) {
        if (aVoice == null || aVoice.isBlank() || !authContext.isAuthenticated()) return;
        final java.util.Set<String> next = new java.util.LinkedHashSet<>(mutedVoices);
        if (!next.add(aVoice.trim())) return;
        try {
            prefsService.save(authContext.getPrincipalName().orElse(""),
                p -> p.setMutedVoices(CommentQueryService.formatMuted(next)));
        } catch (final RuntimeException ex) {
            com.vaadin.flow.component.notification.Notification.show(
                t("prefs.saveFailed"), 4000,
                com.vaadin.flow.component.notification.Notification.Position.TOP_CENTER);
            return;
        }
        mutedVoices = next;
        com.vaadin.flow.component.notification.Notification.show(
            t("reader.comments.muted"), 3000,
            com.vaadin.flow.component.notification.Notification.Position.TOP_CENTER);
        // The reader's whole state lives in the URL, so a reload lands on the same
        // passage with the same columns — the cheapest correct way to drop a voice
        // out of every rendered column and the panel at once, with no bespoke
        // invalidation path to drift out of step with the render code.
        getUI().ifPresent(ui -> ui.getPage().reload());
    }

    private static String channelOf(final VerseComment aComment) {
        // One definition of "whose comment is this", shared with the inline
        // bubbles (V17): channel label, else a [Channel] prefix, else the
        // author's display name. Kept as a thin alias because this panel calls
        // it a "commenter" while the mute list calls it a "voice".
        return CommentQueryService.voiceOf(aComment);
    }

    /** One comment as a card: the argument text plus its action row — cited-verse
     *  buttons (each opens a new unsynced column), \u25B6 video at the verse's
     *  timecode, and the plain YouTube link. Shared by the per-verse dialog and
     *  the browser panel; beforeRefNav (e.g. closing the dialog) runs before a
     *  ref opens its column. {@code anEffectiveLevel} is the caller's bulk-computed
     *  {@link org.religioustext.app.model.user.BasicLevel} name on this comment
     *  ({@code null} for guests) — it decides the ✎ edit affordance. */
    private Div commentCard(final VerseComment aComment, final Runnable aBeforeRefNav,
                            final String anEffectiveLevel) {
        final Div card = new Div();
        // The permalink anchor the ?comment=cmt_… deep link scrolls to + flashes.
        if (aComment.publicId() != null && !aComment.publicId().isBlank())
            card.getElement().setAttribute("data-cid", aComment.publicId());
        card.getStyle()
            .set("padding", "10px 0 14px")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        // Own-comment tags: "your comment", plus "private draft" when unpublished — the
        // author's annotations are overlaid on the public view and marked as theirs.
        if (aComment.own()) {
            final Div tags = new Div();
            tags.getStyle().set("display", "flex").set("gap", "6px").set("margin-bottom", "4px");
            final Span mine = new Span(t("reader.comments.mine"));
            mine.getStyle().set("font-size", "11px").set("font-weight", "600")
                .set("background", "var(--lumo-contrast-10pct)")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("padding", "1px 8px").set("border-radius", "10px");
            tags.add(mine);
            if (aComment.unpublished()) {
                final Span draft = new Span(t("reader.comments.draft"));
                draft.getStyle().set("font-size", "11px").set("font-weight", "600")
                    .set("background", "#ffe1a3").set("color", "#8a5a00")
                    .set("padding", "1px 8px").set("border-radius", "10px");
                tags.add(draft);
            }
            card.add(tags);
        }

        // Someone else's comment: name the voice and offer to stop seeing it.
        // The mute lives HERE, not only in Preferences, because this is where a
        // reader meets a voice they would rather not read — a list you must go
        // and find first is a list nobody curates. Unmuting is Preferences' job:
        // a muted voice has no card left to click.
        final String voice = channelOf(aComment);
        if (!aComment.own() && voice != null && authContext.isAuthenticated()) {
            final Div byline = new Div();
            byline.getStyle().set("display", "flex").set("gap", "8px")
                .set("align-items", "center").set("margin-bottom", "4px");
            // Attribution only for a person's own words. An imported argument
            // already carries its channel on the ▶ button, and repeating it here
            // would just be the same name twice.
            if ((aComment.watchLabel() == null || aComment.watchLabel().isBlank())
                    && aComment.author() != null && !aComment.author().isBlank()) {
                final Span who = new Span(aComment.author());
                who.getStyle().set("font-size", "11px").set("font-weight", "600")
                    .set("color", "var(--lumo-secondary-text-color)");
                byline.add(who);
            }
            final Button mute = new Button(t("reader.comments.mute"), e -> muteVoice(voice));
            mute.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            mute.getElement().setAttribute("title", t("reader.comments.mute.hint"));
            mute.getStyle().set("font-size", "11px")
                .set("color", "var(--lumo-secondary-text-color)");
            byline.add(mute);
            card.add(byline);
        }

        final Div content = new Div();
        content.setText(aComment.content());
        content.getStyle()
            .set("white-space", "pre-wrap")
            .set("font-size", "14px")
            .set("line-height", "1.6");
        card.add(content);

        final Div links = new Div();
        links.getStyle()
            .set("display", "flex")
            .set("flex-wrap", "wrap")
            .set("gap", "6px 14px")
            .set("align-items", "center")
            .set("margin-top", "10px")
            .set("font-size", "13px");

        for (final VerseComment.Ref r : aComment.refs()) {
            final Button refBtn = new Button(refLabel(r), e -> {
                if (aComment.publicId() != null && !aComment.publicId().isBlank()) focusedCommentId = aComment.publicId();
                if (aBeforeRefNav != null) aBeforeRefNav.run();
                openRefInNewColumn(r);
            });
            refBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            refBtn.setTooltipText(t("reader.comments.openInNewColumn"));
            links.add(refBtn);
        }

        // Open every cited passage at once, each in its own column.
        if (aComment.refs().size() > 1) {
            final Button openAll = new Button(
                t("reader.comments.openAll") + " (" + aComment.refs().size() + ")", e -> {
                    if (aComment.publicId() != null && !aComment.publicId().isBlank()) focusedCommentId = aComment.publicId();
                    if (aBeforeRefNav != null) aBeforeRefNav.run();
                    for (final VerseComment.Ref r : aComment.refs()) openRefInNewColumn(r);
                });
            openAll.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            links.add(openAll);
        }

        final String play = aComment.videoUrl() != null ? aComment.videoUrl() : aComment.watchUrl();
        if (play != null) {
            final Button watch = new Button("\u25B6 " + t("reader.comments.watch"),
                e -> openVideoDialog(play, aComment.watchTitle() != null ? aComment.watchTitle() : aComment.watchLabel()));
            watch.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            links.add(watch);
        }
        if (aComment.watchUrl() != null) {
            // Timecoded when the enrichment resolved one for this verse — the plain
            // YouTube link should land at the same moment the ▶ popup does.
            final String ytHref = aComment.videoUrl() != null && !aComment.videoUrl().isBlank()
                ? aComment.videoUrl() : aComment.watchUrl();
            final Anchor yt = new Anchor(ytHref, t("reader.comments.watchOnYoutube")
                + (aComment.watchLabel() != null && !aComment.watchLabel().isBlank() ? " — " + aComment.watchLabel() : ""));
            yt.setTarget("_blank");
            yt.getElement().setAttribute("rel", "noopener noreferrer");
            yt.getStyle().set("color", "var(--lumo-secondary-text-color)");
            links.add(yt);
        }

        // 🔗 Copy a permalink to THIS comment (?comment=cmt_…). The clipboard
        // write happens client-side inside the click gesture (same pattern as the
        // toolbar Copy-link button — the only thing Safari on http reliably
        // allows); the server-side listener only shows the toast.
        if (aComment.publicId() != null && !aComment.publicId().isBlank()) {
            final Button share = new Button(VaadinIcon.LINK.create(),
                e -> Notification.show(t("reader.comments.linkCopied"),
                        2000, Notification.Position.BOTTOM_START));
            share.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            share.setTooltipText(t("reader.comments.copyLink"));
            share.getElement().executeJs(
                  "const btn = this; const path = $0;"
                + "btn.addEventListener('click', () => {"
                + "  const u = window.location.origin + path;"
                + "  let ok = false;"
                + "  try {"
                + "    const ta = document.createElement('textarea'); ta.value = u;"
                + "    ta.style.position='fixed'; ta.style.top='-1000px'; ta.style.opacity='0';"
                + "    document.body.appendChild(ta); ta.focus({preventScroll:true}); ta.select();"
                + "    ok = document.execCommand('copy'); document.body.removeChild(ta);"
                + "  } catch (e) { ok = false; }"
                + "  if (!ok && navigator.clipboard && navigator.clipboard.writeText)"
                + "    navigator.clipboard.writeText(u).catch(() => {});"
                + "});",
                "/reader?comment=" + java.net.URLEncoder.encode(
                    aComment.publicId(), java.nio.charset.StandardCharsets.UTF_8));
            links.add(share);
        }

        // 🔒 Edit this comment's permissions (mode-2 ACL editor). Shown to signed-in readers;
        // the dialog itself enforces canChangeAcl (admin or the comment's owner) and is
        // read-only otherwise.
        if (authContext.isAuthenticated() && aComment.publicId() != null && !aComment.publicId().isBlank()) {
            final Button perms = new Button(VaadinIcon.LOCK.create(), e ->
                new CommentAclDialog(commentAcl, this::t, aComment.publicId(),
                    authContext.getPrincipalName().orElse(""),
                    () -> { }).open());
            perms.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            perms.setTooltipText(t("reader.acl.edit"));
            links.add(perms);
        }

        // ✎ Edit this comment's content — the ACL plane in action: shown when the caller's
        // effective level reaches write (owner, member-group grantee, admin). Delete is offered
        // inside the editor only at level delete. The service re-checks on save regardless.
        final boolean canWrite = "write".equals(anEffectiveLevel) || "delete".equals(anEffectiveLevel);
        if (canWrite && aComment.publicId() != null && !aComment.publicId().isBlank()) {
            final Button edit = new Button(VaadinIcon.EDIT.create(), e ->
                openSharedCommentEditor(aComment, "delete".equals(anEffectiveLevel), aBeforeRefNav));
            edit.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            edit.setTooltipText(t("reader.comments.edit"));
            links.add(edit);
        }
        card.add(links);
        return card;
    }

    /** Where a comment editor's references live — service-backed for an existing comment,
     *  in-memory while composing a new one. One dialog body works against either. */
    private interface RefStore {
        CommentAuthorService.CommentRefs load();
        void add(List<VerseComment.Ref> theRefs);
        void remove(String anId);
        void setExternal(String aUrl, String aLabel);
        /** Id of a ref that must not be removed (the anchor while composing), or null. */
        default String fixedId() { return null; }
    }

    /** Service-backed store for an EXISTING comment: every mutation is ACL-gated and the
     *  comments panel refreshes so the cards' ref buttons track the edit. */
    private RefStore persistedRefs(final String anEmail, final String aPublicId) {
        return new RefStore() {
            @Override public CommentAuthorService.CommentRefs load() {
                return commentAuthor.refsFor(anEmail, aPublicId);
            }
            @Override public void add(final List<VerseComment.Ref> theRefs) {
                commentAuthor.addRefs(anEmail, aPublicId, theRefs);
                afterOwnCommentChange(null);
            }
            @Override public void remove(final String anId) {
                commentAuthor.removeRef(anEmail, aPublicId, anId);
                afterOwnCommentChange(null);
            }
            @Override public void setExternal(final String aUrl, final String aLabel) {
                commentAuthor.setExternalLink(anEmail, aPublicId, aUrl, aLabel);
                afterOwnCommentChange(null);
            }
        };
    }

    /** In-memory store while COMPOSING: the anchor verse is a fixed first chip, extras and the
     *  external link are held locally and persisted right after create. */
    private static final class MemRefs implements RefStore {
        private static final String ANCHOR_ID = "mem-anchor";
        private final List<CommentAuthorService.EditableRef> verses = new ArrayList<>();
        private String extUrl, extLabel;
        private int seq = 0;

        MemRefs(final VerseComment.Ref anAnchor) {
            verses.add(new CommentAuthorService.EditableRef(ANCHOR_ID,
                anAnchor.quran(), anAnchor.bookCode(), anAnchor.chapter(), anAnchor.verse()));
        }
        @Override public CommentAuthorService.CommentRefs load() {
            return new CommentAuthorService.CommentRefs(List.copyOf(verses),
                extUrl == null ? null : new CommentAuthorService.EditableExternal("mem-ext", extUrl, extLabel));
        }
        @Override public void add(final List<VerseComment.Ref> theRefs) {
            for (final VerseComment.Ref r : theRefs) {
                final boolean dup = verses.stream().anyMatch(v ->
                    v.bookCode().equals(r.bookCode()) && v.chapter() == r.chapter() && v.verse() == r.verse());
                if (!dup) verses.add(new CommentAuthorService.EditableRef(
                    "mem-" + (++seq), r.quran(), r.bookCode(), r.chapter(), r.verse()));
            }
        }
        @Override public void remove(final String anId) {
            if (ANCHOR_ID.equals(anId)) return;
            verses.removeIf(v -> v.id().equals(anId));
        }
        @Override public void setExternal(final String aUrl, final String aLabel) {
            if (aUrl == null || aUrl.isBlank()) { extUrl = null; extLabel = null; }
            else { extUrl = aUrl.strip(); extLabel = aLabel == null ? null : aLabel.strip(); }
        }
        @Override public String fixedId() { return ANCHOR_ID; }

        /** Everything except the anchor — persisted with addRefs after create. */
        List<VerseComment.Ref> extras() {
            final List<VerseComment.Ref> out = new ArrayList<>();
            for (final CommentAuthorService.EditableRef v : verses)
                if (!ANCHOR_ID.equals(v.id()))
                    out.add(new VerseComment.Ref(v.quran(), v.bookCode(), v.chapter(), v.verse()));
            return out;
        }
        String extUrl()   { return extUrl; }
        String extLabel() { return extLabel; }
    }

    /** THE comment dialog — create and edit share it, because a comment is text in the
     *  context of passages: content on top, the cited-verse chips + add-parser + external
     *  link below, the public flag when the caller owns the comment, delete when permitted. */
    private void openUnifiedCommentDialog(final String aTitle, final String anInitialContent,
            final RefStore aRefStore, final Checkbox aPublicBoxOrNull, final String aRejectionOrNull,
            final Runnable aDeleteActionOrNull,
            final java.util.function.Consumer<String> aSaveAction, final Runnable anAfterChange) {
        final Dialog dialog = new Dialog();
        dialog.setHeaderTitle(aTitle);
        dialog.setWidth("620px");
        dialog.setMaxWidth("94vw");
        dialog.setDraggable(true);
        dialog.setResizable(true);

        if (aRejectionOrNull != null) {
            final Span rej = new Span(aRejectionOrNull);
            rej.getStyle().set("color", "var(--lumo-error-text-color)").set("font-size", "13px");
            dialog.add(rej);
        }

        final TextArea ta = new TextArea();
        ta.setWidthFull();
        ta.setMinHeight("180px");
        ta.setPlaceholder(t("reader.commentForm.placeholder"));
        ta.setValue(anInitialContent == null ? "" : anInitialContent);
        dialog.add(ta);
        ta.focus();

        final Div refsBox = new Div();
        refsBox.getStyle().set("margin-top", "12px").set("padding-top", "10px")
            .set("border-top", "1px solid var(--lumo-contrast-10pct)");
        dialog.add(refsBox);
        renderEditorRefs(refsBox, aRefStore);

        if (aPublicBoxOrNull != null) dialog.add(aPublicBoxOrNull);

        final Button save = new Button(t("action.save"), e -> {
            final String text = ta.getValue();
            if (text == null || text.isBlank()) { ta.focus(); return; }
            try {
                aSaveAction.accept(text);
                Notification.show(t("reader.commentForm.saved"), 2000, Notification.Position.BOTTOM_START);
                dialog.close();
                anAfterChange.run();
            } catch (final Exception ex) {
                Notification.show(t("reader.commentForm.failed"), 4000, Notification.Position.BOTTOM_START);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        if (aDeleteActionOrNull != null) {
            // Two-step inline confirm: first click arms, second click deletes.
            final Button delete = new Button(t("reader.comments.delete"));
            delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            delete.addClickListener(e -> {
                if (!t("reader.comments.deleteConfirm").equals(delete.getText())) {
                    delete.setText(t("reader.comments.deleteConfirm"));
                    return;
                }
                try {
                    aDeleteActionOrNull.run();
                    Notification.show(t("reader.commentForm.deleted"), 2000, Notification.Position.BOTTOM_START);
                    dialog.close();
                    anAfterChange.run();
                } catch (final Exception ex) {
                    Notification.show(t("reader.commentForm.failed"), 4000, Notification.Position.BOTTOM_START);
                }
            });
            dialog.getFooter().add(delete);
        }
        dialog.getFooter().add(new Button(t("action.cancel"), e -> dialog.close()), save);
        dialog.open();
    }

    /** The card-✎ entry: edit a comment the caller holds WRITE on (delete at DELETE). */
    private void openSharedCommentEditor(final VerseComment aComment, final boolean aCanDelete,
                                         final Runnable aBeforeRefNav) {
        final String email = authContext.getPrincipalName().orElse("");
        if (email.isBlank()) return;
        final Runnable after = () -> {
            if (aBeforeRefNav != null) aBeforeRefNav.run();   // close a stale parent dialog
            afterOwnCommentChange(null);
        };
        openUnifiedCommentDialog(
            t("reader.commentEdit.title") + (channelOf(aComment) != null ? " — " + channelOf(aComment) : ""),
            aComment.content(),
            persistedRefs(email, aComment.publicId()),
            null,   // moderation/public state deliberately untouched on shared edits
            null,
            aCanDelete ? () -> commentAuthor.deleteShared(email, aComment.publicId()) : null,
            text -> commentAuthor.updateShared(email, aComment.publicId(), text),
            after);
    }

    /** (Re)render an editor's references section against its {@link RefStore}: one chip per
     *  cited verse with a ✕ (the composing anchor is fixed), a free-text add input (same
     *  vocabulary as the toolbar ref list — "John 1:1, Rom 9:5"), and the external link's
     *  URL + label. Every change re-renders this box. */
    private void renderEditorRefs(final Div aBox, final RefStore aStore) {
        aBox.removeAll();
        final CommentAuthorService.CommentRefs refs;
        try {
            refs = aStore.load();
        } catch (final RuntimeException ex) {
            aBox.add(new Span(ex.getMessage()));
            return;
        }
        final Runnable rerender = () -> renderEditorRefs(aBox, aStore);

        final Span heading = new Span(t("reader.commentEdit.refs"));
        heading.getStyle().set("font-size", "12px").set("font-weight", "600")
            .set("color", "var(--lumo-secondary-text-color)").set("display", "block")
            .set("margin-bottom", "6px");
        aBox.add(heading);

        final Div chips = new Div();
        chips.getStyle().set("display", "flex").set("flex-wrap", "wrap")
            .set("gap", "6px").set("align-items", "center");
        for (final CommentAuthorService.EditableRef r : refs.verses()) {
            final Div chip = new Div();
            chip.getStyle().set("display", "inline-flex").set("align-items", "center")
                .set("gap", "4px").set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "12px").set("padding", "2px 4px 2px 10px")
                .set("font-size", "13px");
            chip.add(new Span(refLabel(new VerseComment.Ref(r.quran(), r.bookCode(), r.chapter(), r.verse()))));
            if (!r.id().equals(aStore.fixedId())) {
                final Button x = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> {
                    try {
                        aStore.remove(r.id());
                        rerender.run();
                    } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
                });
                x.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
                x.setAriaLabel(t("reader.acl.remove"));
                chip.add(x);
            }
            chips.add(chip);
        }
        aBox.add(chips);

        // Add refs — parsed with the same locale-aware vocabulary as the toolbar ref field.
        final TextField addField = new TextField();
        addField.setPlaceholder(t("reader.commentEdit.addRefs.placeholder"));
        addField.getStyle().set("flex", "1");
        final Button addBtn = new Button(t("reader.commentEdit.addRefs"), e -> {
            final List<VerseComment.Ref> parsed =
                RefListParser.parse(addField.getValue(), LocaleUtil.currentLocale());
            if (parsed.isEmpty()) { Notification.show(t("reader.commentEdit.addRefs.none")); return; }
            try {
                aStore.add(parsed);
                rerender.run();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        addBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        final Div addRow = new Div(addField, addBtn);
        addRow.getStyle().set("display", "flex").set("gap", "6px")
            .set("align-items", "center").set("margin-top", "8px");
        aBox.add(addRow);

        // External link (URL + label) — set to change, clear the URL to remove.
        final TextField url = new TextField();
        url.setPlaceholder(t("reader.commentEdit.link.placeholder"));
        url.setValue(refs.external() == null || refs.external().url() == null ? "" : refs.external().url());
        url.getStyle().set("flex", "2");
        final TextField label = new TextField();
        label.setPlaceholder(t("reader.commentEdit.linkLabel.placeholder"));
        label.setValue(refs.external() == null || refs.external().label() == null ? "" : refs.external().label());
        label.getStyle().set("flex", "1");
        final Button setLink = new Button(t("reader.commentEdit.setLink"), e -> {
            try {
                aStore.setExternal(url.getValue(), label.getValue());
                rerender.run();
            } catch (final RuntimeException ex) { Notification.show(ex.getMessage()); }
        });
        setLink.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        final Div linkRow = new Div(url, label, setLink);
        linkRow.getStyle().set("display", "flex").set("gap", "6px")
            .set("align-items", "center").set("margin-top", "8px");
        aBox.add(linkRow);
    }

    /** Privacy-mode YouTube popup, started at the reference's timecode. The
     *  dialog-open click is the user gesture autoplay needs; a plain YouTube
     *  link rides along since channels can disable embedding. */
    private void openVideoDialog(final String aVideoUrl, final String aTitle) {
        final java.util.regex.Matcher idm = java.util.regex.Pattern
            .compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(aVideoUrl);
        if (!idm.find()) {                        // unparseable — just open the link
            UI.getCurrent().getPage().open(aVideoUrl, "_blank");
            return;
        }
        final java.util.regex.Matcher tm = java.util.regex.Pattern
            .compile("[?&]t=(\\d+)s?").matcher(aVideoUrl);
        final int start = tm.find() ? Integer.parseInt(tm.group(1)) : 0;

        final Dialog dialog = new Dialog();
        if (aTitle != null && !aTitle.isBlank()) dialog.setHeaderTitle(aTitle);
        dialog.setWidth("640px");
        dialog.setMaxWidth("96vw");

        final IFrame frame = new IFrame("https://www.youtube-nocookie.com/embed/"
            + idm.group(1) + "?autoplay=1" + (start > 0 ? "&start=" + start : ""));
        frame.setWidth("100%");
        frame.setHeight("338px");
        frame.getElement().setAttribute("allow",
            "autoplay; encrypted-media; picture-in-picture; fullscreen");
        frame.getElement().setAttribute("allowfullscreen", "true");
        frame.getStyle().set("border", "none");

        final Anchor fallback = new Anchor(aVideoUrl, t("reader.comments.watchOnYoutube"));
        fallback.setTarget("_blank");
        fallback.getElement().setAttribute("rel", "noopener noreferrer");
        fallback.getStyle().set("display", "block").set("margin-top", "8px")
            .set("font-size", "12px").set("color", "var(--lumo-secondary-text-color)");

        dialog.add(frame, fallback);
        dialog.open();
    }

    /** Open a cited verse in a NEW column appended to the layout. The new column
     *  is unsynced so jumping it to the reference cannot drag the existing
     *  columns along; everything already open stays exactly as it was. Default
     *  editions: the user's preferred Bible (else NIV) for Bible refs, the
     *  Arabic Qur'an (translation shown) for Qur'an refs. */
    private void openRefInNewColumn(final VerseComment.Ref aRef) {
        openRefInNewColumn(aRef, sourceByToken(aRef.quran() ? "q-ar" : defaultBibleToken()), false, 0);
    }

    /** As above, highlighting through {@code anEndVerse} (same chapter, or same
     *  surah for a Qur'an ref; {@code 0} = single verse) — the range form the
     *  toolbar passages box accepts, e.g. "Zec 2:8-11". */
    private void openRefInNewColumn(final VerseComment.Ref aRef, final int anEndVerse) {
        openRefInNewColumn(aRef, sourceByToken(aRef.quran() ? "q-ar" : defaultBibleToken()),
                           false, anEndVerse);
    }

    /** As {@link #openRefInNewColumn(VerseComment.Ref)} but opening the verse in a
     *  SPECIFIC edition — used by search's translation picker, where the chosen
     *  translation's source row is passed in. */
    private void openRefInNewColumn(final VerseComment.Ref aRef, final String[] aSelection) {
        openRefInNewColumn(aRef, aSelection, false, 0);
    }

    /** As above, but {@code synced} opens the new column synced so it scrolls
     *  together with the other columns opened in the same action — used when the
     *  search picker opens several translations to compare side by side. A single
     *  open stays unsynced so it never drags the existing columns. */
    private void openRefInNewColumn(final VerseComment.Ref aRef, final String[] aSelection,
                                    final boolean aSyncedFlag) {
        openRefInNewColumn(aRef, aSelection, aSyncedFlag, 0);
    }

    private void openRefInNewColumn(final VerseComment.Ref aRef, final String[] aSelection,
                                    final boolean aSyncedFlag, final int anEndVerse) {
        if (aSelection == null) return;
        final ColState st = addColumn(-1);
        setColumnSync(st, aSyncedFlag);
        selectSource(st, aSelection);
        if (aRef.quran() && st.companionId != null) {  // show the translation beneath the Arabic
            st.showCompanion = true;
            if (st.companionToggle != null) {
                st.companionToggle.setIcon(VaadinIcon.EYE.create());
                st.companionToggle.getStyle().set("color", "var(--lumo-primary-color)");
            }
            refreshBookNames(st);
            updateAttribution(st);
        }
        final String book = bookNameForCode(st, aRef.bookCode());
        final int chapter = aRef.quran() ? 1 : aRef.chapter();
        final int seq = (book == null || st.col.getSourceId() == null) ? -1
            : queryService.seqForRef(st.col.getSourceId(), st.order(), book, chapter, aRef.verse());
        if (seq >= 0) {
            // Verse memory BEFORE openAtSeq: openAtSeq refreshes the copy link
            // internally, and later refreshes are not guaranteed (chapter-visible
            // broadcasts dedupe on an unchanged chapter) — set-after loses the
            // verse on the LAST column opened.
            st.openedRefBook    = book;
            st.openedRefChapter = chapter;
            st.openedRefVerse   = aRef.verse();
            openAtSeq(st, seq);
            if (anEndVerse > aRef.verse())
                flashRange(st, aRef.bookCode(), chapter, aRef.verse(), chapter, anEndVerse);
            else
                flashVerse(st, "v-" + aRef.bookCode() + "-" + chapter + "-" + aRef.verse());
        } else {
            openAtStart(st);
        }
        // The columns row scrolls horizontally; bring the new column into view.
        st.scrollRoot.getElement().executeJs(
            "this.scrollIntoView({behavior:'smooth', block:'nearest', inline:'nearest'})");
    }

    /** Flash hold = orientation base (+ per extra column, so an "Open all"
     *  has time to be looked at) + a reading allowance proportional to the
     *  highlighted text. 80ms/char is ~12 chars/s, slow enough to narrate over:
     *  a one-verse flash stays short, a multi-verse span earns its time. The
     *  allowance is only measurable in the DOM, so the base is passed in and
     *  the total is fixed on the first tick that finds painted text. */
    private static final int FLASH_BASE_MS       = 12000;
    private static final int FLASH_PER_COLUMN_MS = 4000;
    private static final int FLASH_PER_CHAR_MS   = 80;
    private static final int FLASH_MAX_MS        = 120000;

    /** Focus + highlight the referenced verse in the new column. The interval
     *  re-queries the verse anchor every tick because the column's observers
     *  reload/rebuild its content right after opening (replacing the painted
     *  node and resetting the scroll to the chapter top). For the first ~2.5s
     *  it keeps the verse CENTERED in the column — not merely visible — so the
     *  surrounding context shows above and below; after that the user's own
     *  scrolling is left alone while the highlight persists. The hold time
     *  scales with how many columns are open (e.g. after "Open all"), so
     *  there's time to look at every one. */
    private void flashVerse(final ColState aState, final String anAnchorId) {
        final int holdMs = FLASH_BASE_MS + Math.max(0, states.size() - 1) * FLASH_PER_COLUMN_MS;
        aState.scrollRoot.getElement().executeJs(
              "const sel = '#' + CSS.escape($0);"
            + "let until = 0;"
            + "const settle = Date.now() + 3000;"
            + "const paint = (el, on) => {"
            + "  if (!el) return;"
            + "  el.style.transition = 'background-color .4s';"
            + "  el.style.backgroundColor = on ? 'rgba(255,235,59,.45)' : '';"
            + "};"
            + "const timer = setInterval(() => {"
            + "  const el = this.querySelector(sel);"
            + "  if (el && !until) {"
            + "    const n = el.nextElementSibling;"
            + "    until = Date.now() + Math.min($1 + (n ? n.textContent.length : 0) * "
            +          FLASH_PER_CHAR_MS + ", " + FLASH_MAX_MS + ");"
            + "  }"
            + "  const done = until > 0 && Date.now() >= until;"
            + "  if (el) {"
            + "    if (Date.now() < settle) {"
            + "      const r = el.getBoundingClientRect();"
            + "      const c = this.getBoundingClientRect();"
            + "      const off = (r.top + r.bottom) / 2 - (c.top + c.bottom) / 2;"
            + "      if (Math.abs(off) > c.height * 0.2)"
            + "        el.scrollIntoView({block:'center', inline:'nearest'});"
            + "    }"
            + "    paint(el, !done); paint(el.nextElementSibling, !done);"
            + "  }"
            + "  if (done) clearInterval(timer);"
            + "}, 250);", anAnchorId, holdMs);
    }

    /** Paint several passages at once, in any books — the {@code cN.hl} list.
     *
     *  {@link #flashRange} cannot do this: it tests one book code and one
     *  chapter/verse interval. That is the right model for "this passage", but
     *  a reordered edition needs "these passages", and they are in different
     *  books by definition — the whole point of a chronological link is that
     *  Psalm 90 now sits against the end of Deuteronomy. So this takes a list
     *  of specs and turns the bounds check into a membership test; the DOM walk
     *  is unchanged, and unlike flashRange it never scrolls, leaving the
     *  landing position to the anchor ref.
     *
     *  A ref with no verse ({@code PSA.90}) means the whole chapter, so its
     *  span runs verse 1 to Integer.MAX_VALUE.
     */
    private void flashSpans(final ColState aState, final List<ReaderLink.Ref> theSpans) {
        if (theSpans == null || theSpans.isEmpty()) return;
        // Built as a JSON string and parsed client-side rather than via
        // elemental.json: executeJs takes String parameters natively, and this
        // keeps the method free of a Vaadin-internal JSON type that has moved
        // package between major versions.
        final StringBuilder json = new StringBuilder("[");
        int i = 0;
        for (final ReaderLink.Ref r : theSpans) {
            if (r == null) continue;
            final String code = r.quran() ? String.valueOf(r.a()) : r.unit();
            if (code == null || !code.matches("[A-Za-z0-9]{1,5}")) continue;
            final int chap  = r.quran() ? 1 : r.a();
            final int start = r.b() > 0 ? r.b() : 1;
            final int endC  = r.hasEnd() && !r.quran() ? r.endA() : chap;
            final int endV  = r.hasEnd() ? r.endB()
                                         : (r.b() > 0 ? r.b() : Integer.MAX_VALUE);
            if (i > 0) json.append(',');
            json.append("[\"").append(code).append("\",")
                .append(chap).append(',').append(start).append(',')
                .append(endC).append(',').append(endV).append(']');
            i++;
        }
        json.append(']');
        if (i == 0) return;
        final int holdMs = FLASH_BASE_MS + Math.max(0, states.size() - 1) * FLASH_PER_COLUMN_MS;
        aState.scrollRoot.getElement().executeJs(
              "const specs = JSON.parse($0);"
            + "let until = 0, chars = 0;"
            + "const paint = (el, on) => {"
            + "  if (!el) return;"
            + "  el.style.transition = 'background-color .4s';"
            + "  el.style.backgroundColor = on ? 'rgba(255,235,59,.45)' : '';"
            + "};"
            + "const hit = (code, c, v) => specs.some(s =>"
            + "  s[0] === code"
            + "  && (c > s[1] || (c === s[1] && v >= s[2]))"
            + "  && (c < s[3] || (c === s[3] && v <= s[4])));"
            + "const timer = setInterval(() => {"
            + "  const hits = [];"
            + "  this.querySelectorAll('span[id]').forEach(el => {"
            + "    const p = el.id.split('-');"
            + "    if (p.length !== 4 || p[0] !== 'v') return;"
            + "    const c = parseInt(p[2], 10), v = parseInt(p[3], 10);"
            + "    if (hit(p[1], c, v)) hits.push(el);"
            + "  });"
            + "  if (!until && hits.length) {"
            + "    hits.forEach(el => { const n = el.nextElementSibling;"
            + "      chars += n ? n.textContent.length : 0; });"
            + "    until = Date.now() + Math.min($1 + chars * "
            +          FLASH_PER_CHAR_MS + ", " + FLASH_MAX_MS + ");"
            + "  }"
            + "  const done = until > 0 && Date.now() >= until;"
            + "  hits.forEach(el => { paint(el, !done); paint(el.nextElementSibling, !done); });"
            + "  if (done) clearInterval(timer);"
            + "}, 250);", json.toString(), holdMs);
    }

    /** Range twin of {@link #flashVerse}: paints every verse-number span of
     *  {@code aBookCode} between (start chapter, start verse) and (end chapter,
     *  end verse) INCLUSIVE — each span and its following text span — with the
     *  same interval-driven yellow flash, so a link can present a whole passage
     *  (the full Servant Song, a paragraph around a famous verse) rather than a
     *  single verse. The interval re-queries every tick, so verses that render
     *  later (windowed loading) join the highlight. The settle-scroll centres
     *  the range START. Verse ids only exist when verse numbers are shown, so
     *  other display modes no-op — exactly like {@link #flashVerse}.
     *
     * @param aStartChapter for a Qur'an range both chapters are 1 (the surah
     *                      lives in {@code aBookCode}; ranges never cross one)
     */
    private void flashRange(final ColState aState, final String aBookCode,
                            final int aStartChapter, final int aStartVerse,
                            final int anEndChapter, final int anEndVerse) {
        final int holdMs = FLASH_BASE_MS + Math.max(0, states.size() - 1) * FLASH_PER_COLUMN_MS;
        aState.scrollRoot.getElement().executeJs(
              "const code = $0, c1 = $1, v1 = $2, c2 = $3, v2 = $4;"
            + "let until = 0, chars = 0;"
            + "const settle = Date.now() + 3000;"
            + "const inRange = (c, v) =>"
            + "  (c > c1 || (c === c1 && v >= v1)) && (c < c2 || (c === c2 && v <= v2));"
            + "const paint = (el, on) => {"
            + "  if (!el) return;"
            + "  el.style.transition = 'background-color .4s';"
            + "  el.style.backgroundColor = on ? 'rgba(255,235,59,.45)' : '';"
            + "};"
            + "const timer = setInterval(() => {"
            + "  const hits = []; let start = null;"
            + "  this.querySelectorAll('span[id]').forEach(el => {"
            + "    const p = el.id.split('-');"
            + "    if (p.length !== 4 || p[0] !== 'v' || p[1] !== code) return;"
            + "    const c = parseInt(p[2], 10), v = parseInt(p[3], 10);"
            + "    if (!inRange(c, v)) return;"
            + "    if (c === c1 && v === v1) start = el;"
            + "    hits.push(el);"
            + "  });"
            + "  if (!until && hits.length) {"
            + "    hits.forEach(el => { const n = el.nextElementSibling;"
            + "      chars += n ? n.textContent.length : 0; });"
            + "    until = Date.now() + Math.min($5 + chars * "
            +          FLASH_PER_CHAR_MS + ", " + FLASH_MAX_MS + ");"
            + "  }"
            + "  const done = until > 0 && Date.now() >= until;"
            + "  hits.forEach(el => { paint(el, !done); paint(el.nextElementSibling, !done); });"
            + "  if (start && Date.now() < settle) {"
            + "    const r = start.getBoundingClientRect();"
            + "    const c = this.getBoundingClientRect();"
            + "    const off = (r.top + r.bottom) / 2 - (c.top + c.bottom) / 2;"
            + "    if (Math.abs(off) > c.height * 0.2)"
            + "      start.scrollIntoView({block:'center', inline:'nearest'});"
            + "  }"
            + "  if (done) clearInterval(timer);"
            + "}, 250);",
            aBookCode, aStartChapter, aStartVerse, anEndChapter, anEndVerse, holdMs);
    }

    private static String refLabel(final VerseComment.Ref aRef) {
        return aRef.quran()
            ? "Q " + aRef.bookCode() + ":" + aRef.verse()
            : aRef.bookCode() + " " + aRef.chapter() + ":" + aRef.verse();
    }

    // ── Forward / backward window fill ────────────────────────────────────────
    // loadNext / loadPrev + lastRenderedBook/Chapter, firstRenderedBook,
    // stripChapterHeading, renderedGroups, trimTop/trimBottom, removeSeparatorAbove
    // moved to VerseWindowRenderer; the scroll observer fires into renderer.load*.

    // ── Scroll observer ───────────────────────────────────────────────────────
    //
    // The sentinels + IntersectionObserver/MutationObserver machinery + the
    // scroll-anchor jump JS live in ReaderScrollController (same package).
    // ReaderView keeps only the shared-position reaction the controller calls
    // back into, below.

    /** The scroll observer reported a new top-of-viewport chapter for a column (a
     *  user scroll). When this column leads the sync group, push it to the shared
     *  position; always refresh the copy-link target. Runs on the UI thread. */
    private void onVisibleChapterChanged(final ColState aState, final String aBook,
                                         final int aChapter, final int aSeq) {
        final boolean suppressed = System.currentTimeMillis() < aState.suppressUntil;
        if (aState.col.isSynced() && !suppressed) {
            currentSeq = aSeq;
            currentBook = aBook;
            currentChapter = aChapter;
            notifyOthers(aState);
        }
        refreshCopyLink();
    }


    // ── Sync (seq-based) ──────────────────────────────────────────────────────

    private void syncOthers(final ColState aSource)  { syncFrom(aSource); }
    private void notifyOthers(final ColState aSource) { syncFrom(aSource); }

    private void syncFrom(final ColState aSource) {
        for (final ColState s : states) {
            if (s == aSource || !s.col.isSynced() || s.col.getSourceId() == null) continue;
            syncTo(s);
        }
    }

    /**
     * Bring a synced follower to the shared position. The shared position is the
     * leader's (book, chapter) — resolve it to THIS column's own active-seq (a
     * follower in a different order or translation maps the same passage to a
     * different seq). Scroll if loaded, else re-open the window there.
     */
    private void syncTo(final ColState aState) {
        if (currentBook == null || aState.col.getSourceId() == null) return;
        final int idx = aState.indexOfBook(currentBook);
        if (idx < 0) return;   // this translation lacks that book name
        final int seq = queryService.seqForBookChapter(
            aState.col.getSourceId(), aState.order(), currentBook, currentChapter);
        if (seq < 0) return;
        if (idx == aState.bookIndex && aState.visibleChapter == currentChapter) return;  // already there
        aState.suppressUntil = System.currentTimeMillis() + 700;
        if (seq >= aState.firstSeq && seq <= aState.lastSeq) {
            scrollController.scrollToSeqAnchor(aState, currentBook, currentChapter, false);
            aState.setVisible(currentChapter, currentBook);
            aState.visibleSeq = seq;
        } else {
            openAtSeq(aState, seq);
        }
    }
}
