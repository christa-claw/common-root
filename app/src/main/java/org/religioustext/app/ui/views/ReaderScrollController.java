package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Div;

/**
 * The reader's infinite-scroll machinery for a single column: the top/bottom
 * sentinels, the client-side IntersectionObserver + MutationObserver that arm
 * forward/backward loads and keep the reading position fixed across trims and
 * prepends, and the scroll-to-anchor jump used by prev/next and sync.
 *
 * <p>Extracted from ReaderView (2026-07-01). ReaderView keeps the actual window
 * fill ({@code loadNext}/{@code loadPrev}) and the shared-sync reaction; this
 * class calls back into them through {@link Host}. The relocation is verbatim —
 * the observer JS is unchanged — so a scroll smoke-test is the real verification
 * (that JS is opaque to the compiler).
 */
final class ReaderScrollController {

    /** ReaderView-side hooks the scroll machinery calls back into (all invoked on
     *  the UI thread via {@code ui.access}). */
    interface Host {
        void loadPrev(ColState aState);
        void loadNext(ColState aState);
        /** The top-of-viewport chapter changed from a user scroll: push it to the
         *  shared sync position when this column leads, and refresh the copy link. */
        void onVisibleChapterChanged(ColState aState, String aBook, int aChapter, int aSeq);
    }

    private final Host host;

    ReaderScrollController(final Host aHost) { this.host = aHost; }

    /** Wire the sentinels, the server-side load listeners, and the client-side
     *  observers for a column. Idempotent (guarded by {@code aState.observerReady})
     *  so the first column's attach-before-add and later runtime adds each set up
     *  exactly once. */
    void setupObserver(final ColState aState) {
        if (aState.observerReady) return;
        aState.observerReady = true;
        final String vcId  = "vc-"  + aState.uid;
        final String topId = "top-" + aState.uid;
        final String botId = "bot-" + aState.uid;
        final String clId  = aState.scrollRoot.getId().orElse("");

        aState.content.setId(vcId);

        final Div top = new Div(); top.setId(topId); top.getStyle().set("height", "1px");
        final Div bot = new Div(); bot.setId(botId); bot.getStyle().set("height", "1px");
        aState.botSentinel = bot;
        aState.content.getElement().insertChild(0, top.getElement());
        aState.content.add(bot);

        top.getElement().addEventListener("load-prev", ev ->
            aState.scrollRoot.getUI().ifPresent(ui -> ui.access(() -> host.loadPrev(aState))));
        bot.getElement().addEventListener("load-next", ev ->
            aState.scrollRoot.getUI().ifPresent(ui -> ui.access(() -> host.loadNext(aState))));

        aState.content.getElement().addEventListener("chapter-visible", ev -> {
            final String ch   = ev.getEventData().getString("event.detail.chapter");
            final String book = ev.getEventData().getString("event.detail.book");
            final String sq   = ev.getEventData().getString("event.detail.seq");
            try {
                final int c = Integer.parseInt(ch);
                aState.scrollRoot.getUI().ifPresent(ui -> ui.access(() -> {
                    aState.setVisible(c, book);
                    try { aState.visibleSeq = Integer.parseInt(sq); } catch (final Exception ignored) {}
                    host.onVisibleChapterChanged(aState, book, c, aState.visibleSeq);
                }));
            } catch (final NumberFormatException ignored) {}
        }).addEventData("event.detail.chapter")
          .addEventData("event.detail.book")
          .addEventData("event.detail.seq");

        aState.scrollRoot.getUI().ifPresent(ui -> ui.getPage().executeJs("""
            (function() {
                const root = document.getElementById($0);
                const vc   = document.getElementById($1);
                const top  = document.getElementById($2);
                const bot  = document.getElementById($3);
                if (!root || !vc || !top || !bot) {
                    console.warn('ScrollObserver missing:', $0, $1, $2, $3);
                    return;
                }
                const fireLoad = (el, type) =>
                    el.dispatchEvent(new CustomEvent(type, {bubbles:true}));

                const BOT_MARGIN = 800;   // px from bottom that arms a forward load
                const TOP_MARGIN = 800;   // px from top that arms a backward load

                let pendingNext   = false;
                let pendingPrev   = false;
                let lastScrollTop = 0;

                // Hard bound on consecutive AUTO forward-fills (the mutation /
                // anchor feedback loop). The budget is replenished ONLY by genuine
                // user input (wheel / touch listeners below), NEVER by 'scroll'
                // events — because programmatic scrollTop adjustments (the trim and
                // prepend anchors) also fire 'scroll', and letting those refill the
                // budget defeats the cap and lets a feedback loop run away through
                // the whole text (the "to Revelation" runaway).
                let autoFills = 0;
                const AUTO_FILL_CAP = 6;

                const fillForward = () => {
                    if (root.hasAttribute('data-jumping')) return;
                    if (autoFills >= AUTO_FILL_CAP) return;
                    const rootRect = root.getBoundingClientRect();
                    const botRect  = bot.getBoundingClientRect();
                    if (botRect.top < rootRect.bottom + BOT_MARGIN) {
                        if (!pendingNext) { pendingNext = true; autoFills++; captureFwdAnchor(); console.log('[CR] fire-fwd autoFills', autoFills, 'botGap', Math.round(botRect.top - rootRect.bottom)); fireLoad(bot, 'load-next'); }
                    } else {
                        pendingNext = false;
                    }
                };

                const sentinel = new IntersectionObserver(entries => {
                    entries.forEach(e => {
                        if (!e.isIntersecting) return;
                        if (root.hasAttribute('data-jumping')) return;
                        if (e.target === bot && !pendingNext) {
                            pendingNext = true; captureFwdAnchor(); fireLoad(bot, 'load-next');
                        }
                    });
                }, { root, rootMargin: BOT_MARGIN + 'px', threshold: 0 });
                sentinel.observe(bot);

                const HEADER_OFFSET = 96;
                const topVisibleChapter = () => {
                    const limit = root.getBoundingClientRect().top + HEADER_OFFSET;
                    let best = null, bestTop = -Infinity, first = null;
                    root.querySelectorAll('[data-chapter]').forEach(el => {
                        if (!first) first = el;
                        const t = el.getBoundingClientRect().top;
                        if (t <= limit && t > bestTop) { bestTop = t; best = el; }
                    });
                    return best || first;
                };

                // Forward-trim anchoring. Captured just before a forward load
                // fires: the on-screen group at the viewport top + its offsetTop
                // (position within the content, independent of scrollTop). After
                // the load appends below and trims above, the same group's
                // offsetTop drops by exactly the trimmed-above height; we add that
                // delta back to scrollTop so the view does not lurch forward.
                const captureFwdAnchor = () => {
                    const a = topVisibleChapter();
                    if (a) { root.__fwdSeq = a.getAttribute('data-seq'); root.__fwdOff = a.offsetTop; }
                    else   { root.__fwdSeq = null; }
                };

                let lastPosKey = '';
                const broadcastPos = () => {
                    if (root.hasAttribute('data-jumping')) return;
                    if (Date.now() < (root.__crIgnoreUntil || 0)) return;
                    const el = topVisibleChapter();
                    if (!el) return;
                    const bk = el.getAttribute('data-book');
                    const ch = el.getAttribute('data-chapter');
                    const sq = el.getAttribute('data-seq');
                    if (!bk || !ch) return;
                    const key = bk + '|' + ch;
                    if (key === lastPosKey) return;
                    lastPosKey = key;
                    vc.dispatchEvent(new CustomEvent('chapter-visible',
                        {bubbles:true, detail:{chapter:ch, book:bk, seq:(sq||'')}}));
                };

                new MutationObserver(ms => {
                    const added = new Set();
                    let removed = false;
                    ms.forEach(m => {
                        m.addedNodes.forEach(n => { if (n.nodeType === 1) added.add(n); });
                        m.removedNodes.forEach(n => {
                            if (n.nodeType === 1 && n.hasAttribute
                                && (n.hasAttribute('data-chapter') || n.hasAttribute('data-separator'))) {
                                removed = true;
                            }
                        });
                    });

                    // First non-separator chunk below the top sentinel — is it new?
                    let topAddedChunk = null;
                    {
                        let n = top.nextElementSibling;
                        while (n && n !== bot) {
                            if (n.hasAttribute && n.hasAttribute('data-chapter')
                                && !n.hasAttribute('data-separator')) {
                                if (added.has(n)) topAddedChunk = n;
                                break;
                            }
                            n = n.nextElementSibling;
                        }
                    }
                    const isTopPrepend = topAddedChunk !== null;
                    console.log('[CR] mut add', added.size, 'rem', removed,
                        'prepend', isTopPrepend, 'autoFills', autoFills,
                        'sTop', Math.round(root.scrollTop),
                        'botGap', Math.round(bot.getBoundingClientRect().top - root.getBoundingClientRect().bottom));

                    if (!root.hasAttribute('data-jumping') && isTopPrepend) {
                        // Backward prepend: shift scrollTop down by the height of
                        // the newly prepended run so the reading position stays
                        // fixed and the top sentinel slides out of view (re-arming
                        // the next load). Forward-scroll top-trims rely on the
                        // browser's native scroll-anchoring (content has
                        // overflow-anchor:auto, the sticky bar has
                        // overflow-anchor:none so it can't capture the anchor).
                        let prependedH = 0;
                        let node = top.nextElementSibling;
                        while (node && node !== bot) {
                            if (added.has(node)) prependedH += node.offsetHeight;
                            else if (node.hasAttribute && node.hasAttribute('data-chapter')
                                     && !node.hasAttribute('data-separator')) break;
                            node = node.nextElementSibling;
                        }
                        if (prependedH > 0) root.scrollTop += prependedH;
                    }

                    if (isTopPrepend) {
                        pendingPrev = false;
                        pendingNext = true;
                        setTimeout(() => { pendingNext = false; fillForward(); }, 400);
                    } else {
                        pendingNext = false;
                        // Forward-trim compensation. A forward load appends below
                        // AND trims above; the trim shortens content above the
                        // viewport. With browser anchoring off, that would lurch
                        // the view forward and the bot sentinel back into range
                        // (the runaway). Restore the anchor captured at fire-time:
                        // its offsetTop dropped by the trimmed-above height, so add
                        // that delta back to scrollTop. Only when a trim actually
                        // happened, and never mid-jump.
                        if (!root.hasAttribute('data-jumping') && removed && root.__fwdSeq) {
                            const a = vc.querySelector('[data-seq="' + root.__fwdSeq + '"]');
                            if (a) {
                                const d = a.offsetTop - root.__fwdOff;
                                console.log('[CR] fwd-comp delta', Math.round(d));
                                root.scrollTop += d;
                                lastScrollTop = root.scrollTop;   // don't read the adjust as an up-scroll
                            } else {
                                console.log('[CR] fwd-comp anchor-LOST seq', root.__fwdSeq);
                            }
                        }
                        root.__fwdSeq = null;
                        fillForward();
                    }
                }).observe(vc, {childList:true, subtree:true});

                root.addEventListener('scroll', () => {
                    if (root.hasAttribute('data-jumping')) return;
                    const st      = root.scrollTop;
                    const goingUp = st < lastScrollTop;
                    lastScrollTop = st;

                    const rootRect = root.getBoundingClientRect();
                    const botRect  = bot.getBoundingClientRect();
                    const topRect  = top.getBoundingClientRect();

                    if (!goingUp) {
                        if (botRect.top >= rootRect.bottom + BOT_MARGIN) pendingNext = false;
                        fillForward();
                    }

                    if (goingUp && !pendingPrev && topRect.bottom > rootRect.top - TOP_MARGIN) {
                        pendingPrev = true;
                        fireLoad(top, 'load-prev');
                        setTimeout(() => { pendingPrev = false; }, 2000);
                    }

                    broadcastPos();
                }, { passive: true });

                // Replenish the forward-fill budget ONLY on genuine user input
                // (wheel / touch), NEVER on 'scroll' — programmatic scrollTop
                // changes (trim + prepend anchors) fire 'scroll' too, and letting
                // them refill the budget defeats the AUTO_FILL_CAP and allows the
                // runaway. With this, any feedback loop is bounded to that cap.
                ['wheel','touchmove'].forEach(evt =>
                    root.addEventListener(evt, () => { autoFills = 0; }, {passive:true}));

                // Jump to a specific (book, chapter) anchor after an openAtSeq:
                // scroll that group to just below the sticky bar, then clear
                // data-jumping and fill. (Sticky-bar offset: a bare
                // scrollIntoView(block:'start') puts the anchor UNDER the
                // overlaying header/nav — same compensation as the deep-link
                // fallback and scrollToSeqAnchor.)
                root._jumpToAnchor = (book, chap) => {
                    root.setAttribute('data-jumping', 'true');
                    root.__crIgnoreUntil = Date.now() + 900;
                    sentinel.disconnect();
                    pendingPrev = false; pendingNext = false;
                    const sel = '[data-book="' + book + '"][data-chapter="' + chap + '"]';
                    setTimeout(() => {
                        const el = root.querySelector(sel);
                        if (el) {
                            const bar = root.firstElementChild;
                            const off = bar ? bar.offsetHeight : 0;
                            root.scrollTop += el.getBoundingClientRect().top
                                            - root.getBoundingClientRect().top - off;
                        }
                        else root.scrollTop = 0;
                        lastScrollTop = root.scrollTop;
                        setTimeout(() => {
                            sentinel.observe(bot);
                            pendingPrev = false; pendingNext = false;
                            autoFills = 0;
                            root.removeAttribute('data-jumping');
                            fillForward();
                        }, 60);
                    }, 40);
                };

                setTimeout(fillForward, 300);
                console.log('ScrollObserver ready on', $0);
            })();
        """, clId, vcId, topId, botId));
    }

    /** Scroll an already-rendered (book, chapter) group to just below the
     *  sticky bar (same sticky-offset compensation as the jump paths). */
    void scrollToSeqAnchor(final ColState aState, final String aBook,
                           final int aChapter, final boolean aSmooth) {
        final String colId = aState.scrollRoot.getId().orElse("");
        aState.scrollRoot.getUI().ifPresent(ui -> ui.getPage().executeJs("""
            (function() {
                const root = document.getElementById($0);
                if (!root) return;
                root.__crIgnoreUntil = Date.now() + 900;
                const el = root.querySelector('[data-book="' + $1 + '"][data-chapter="' + $2 + '"]');
                if (el) {
                    const bar = root.firstElementChild;
                    const off = bar ? bar.offsetHeight : 0;
                    const target = root.scrollTop + el.getBoundingClientRect().top
                                 - root.getBoundingClientRect().top - off;
                    root.scrollTo({top: target, behavior: ($3 === 'true') ? 'smooth' : 'auto'});
                }
            })();
        """, colId, aBook, String.valueOf(aChapter), String.valueOf(aSmooth)));
    }
}
