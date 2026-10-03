package org.religioustext.app.ui.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * Play control for one chapter's generated audio, with the text following along.
 *
 * <p>First version. The button lazily builds a plain {@code <audio controls>} on
 * first click — nothing is fetched until a reader actually asks for it, so a
 * page of twenty chapters costs twenty buttons, not twenty megabytes.
 *
 * <p>HOW THE TEXT FOLLOWS. The generator writes a companion JSON per chapter
 * mapping verse number to a millisecond offset, and the reader gives every verse
 * number a DOM id ({@code v-GEN-1-5}). So the sync is: read the offsets once,
 * and on each {@code timeupdate} mark the last verse whose offset has passed.
 * If the JSON is missing the audio still plays; the text simply does not follow.
 *
 * <p>WHY THE COLUMN ROOT IS REQUIRED. Those verse ids are NOT unique across the
 * reader: two columns showing Genesis 1 in different editions both emit
 * {@code id="v-GEN-1-5"}, so {@code getElementById} would return whichever
 * column rendered first and the wrong text would light up. Every lookup is
 * therefore scoped to this column's own root ({@code col-<uid>}), which IS
 * unique, using an attribute selector rather than an id selector so book codes
 * that start with a digit (1SA, 2KI) can never produce an invalid one.
 *
 * <p>ONE AT A TIME. Each player announces itself on {@code cr-audio-play}; every
 * other player pauses and drops its highlight when it hears one. Otherwise two
 * columns could read aloud over each other, which in a side-by-side reader is
 * not a hypothetical.
 *
 * <p>RUNGS AND COMPANIONS make the job EASIER, not harder: those modes wrap each
 * verse in its own element, so the wrapper is marked and the rung lines beside it
 * are left alone. It is the plain default that needs care — see {@code nodesFor}.
 *
 * <p>LIMITATION: verse ids exist only when verse numbers are shown. Continuous
 * and Chapters modes suppress them deliberately, so playback there works but
 * the text does not highlight.
 */
public class ChapterAudioPlayer extends Div {

    private static final String HIGHLIGHT_CSS = """
        .cr-audio-on {
            background: var(--lumo-primary-color-10pct);
            border-radius: 3px;
            /* The default reader lays verses out as a flat inline run, so a
               highlight often spans several line boxes; clone makes the
               background wrap cleanly instead of drawing one huge rectangle. */
            -webkit-box-decoration-break: clone;
            box-decoration-break: clone;
            transition: background 120ms linear;
        }

        /* The browser's native <audio controls> seek bar renders its played
           and unplayed segments in near-identical greys (worst on Chrome,
           and Firefox/Safari expose no styling hooks for it at all), so
           progress is hard to read at a glance. accent-color nudges Chromium's
           own scrubber toward this color; the track/fill pair below is the
           real fix, since it is ours to color and works in every browser. */
        .cr-audio-progress-track {
            height: 4px;
            border-radius: 2px;
            background: var(--lumo-contrast-10pct);
            margin-top: 4px;
            overflow: hidden;
        }
        .cr-audio-progress-fill {
            height: 100%;
            width: 0%;
            background: var(--lumo-primary-color);
            border-radius: 2px;
            transition: width 100ms linear;
        }
        .cr-audio-caption {
            font-size: var(--lumo-font-size-xxs);
            color: var(--lumo-secondary-text-color);
            margin-top: 2px;
        }
        """;

    private static final String JS = """
        const host = this;
        if (host._crAudio) {
            host._crAudio.paused ? host._crAudio.play() : host._crAudio.pause();
            return;
        }
        if (!document.getElementById('cr-audio-style')) {
            const st = document.createElement('style');
            st.id = 'cr-audio-style';
            st.textContent = $5;
            document.head.appendChild(st);
        }
        const audio = document.createElement('audio');
        audio.controls = true;
        audio.preload = 'none';
        audio.src = $0;
        audio.style.width = '100%';
        audio.style.marginTop = '6px';
        // Best-effort: Chromium's own scrubber honors accent-color on
        // recent versions. Firefox/Safari ignore it harmlessly; the custom
        // track/fill built below is what actually guarantees visibility.
        audio.style.setProperty('accent-color', 'var(--lumo-primary-color)');
        // The host div shrink-wraps to the play button (~24px), so a plain
        // width:100% on the audio resolves to 24px of unusable player.
        host.style.width = '100%';

        // STICKY LIKE THE PAGE HEADER, for the length of THIS chapter only.
        // A sticky element unsticks the moment its containing block's bottom
        // edge passes the offset — and the containing block of a flex item is
        // the flex container. Left inside 'bar' (heading + button, flex-wrap,
        // maybe 60px tall), the player would unstick almost immediately, not
        // stay pinned while reading on. So it is promoted out of 'bar' to be
        // a direct child of the per-chapter group div instead — the box that
        // actually spans the whole chapter, heading through last verse — right
        // after 'bar', where it already visually sat once flex-wrap dropped it
        // to its own line. That group div is 'bar's parent (see
        // VerseWindowRenderer: div.add(bar) then div.add(text)); only done
        // once, since a later click short-circuits above before reaching here.
        const bar = host.parentElement;
        const group = bar ? bar.parentElement : null;
        if (group) group.insertBefore(host, bar.nextSibling);

        // Pinned beneath the column's own sticky header (same technique:
        // sticky, opaque so verses don't show through) rather than at the
        // viewport top. The header's height varies (attribution line, wrapped
        // nav), so it is measured rather than guessed; the column's sticky bar
        // is always its scroll root's first child (see ReaderView.buildColumn).
        const columnRoot = document.getElementById($4);
        const stickyBar = columnRoot ? columnRoot.firstElementChild : null;
        host.style.position = 'sticky';
        host.style.top = (stickyBar ? stickyBar.getBoundingClientRect().height : 0) + 'px';
        host.style.zIndex = '9';
        host.style.background = 'var(--lumo-base-color)';
        host.style.borderBottom = '1px solid var(--lumo-contrast-10pct)';
        host.style.paddingBottom = '6px';
        host.appendChild(audio);
        host._crAudio = audio;

        const track = document.createElement('div');
        track.className = 'cr-audio-progress-track';
        const fill = document.createElement('div');
        fill.className = 'cr-audio-progress-fill';
        track.appendChild(fill);
        host.appendChild(track);

        // Click or drag anywhere on the track to seek. audio.duration is
        // NaN until metadata loads (preload='none'), which is already true
        // by the time a reader can see the bar since play() runs below.
        track.style.cursor = 'pointer';
        const seekTo = clientX => {
            if (!audio.duration) return;
            const rect = track.getBoundingClientRect();
            const ratio = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
            audio.currentTime = ratio * audio.duration;
            fill.style.width = (ratio * 100) + '%';
        };
        let seeking = false;
        track.addEventListener('pointerdown', e => {
            seeking = true;
            track.setPointerCapture(e.pointerId);
            seekTo(e.clientX);
        });
        track.addEventListener('pointermove', e => { if (seeking) seekTo(e.clientX); });
        track.addEventListener('pointerup', () => { seeking = false; });

        const caption = document.createElement('div');
        caption.className = 'cr-audio-caption';
        caption.textContent = 'Audio generated with Azure AI';
        host.appendChild(caption);

        let marks = null;
        fetch($1).then(r => r.ok ? r.json() : null).then(j => {
            if (!j || !j.verses) return;
            marks = Object.entries(j.verses)
                          .map(e => [Number(e[0]), Number(e[1])])
                          .filter(e => !isNaN(e[0]) && !isNaN(e[1]))
                          .sort((a, b) => a[1] - b[1]);
        }).catch(() => {});

        // Scoped to THIS column: verse ids repeat across columns.
        //
        // TWO DOM SHAPES. With translation rungs or a companion open, each verse
        // is wrapped in its own element and the whole wrapper can be marked.
        // In the DEFAULT reader there is no per-verse wrapper at all: the number
        // span and the body span are flat siblings in one inline run, so marking
        // the parent would light up the entire chapter. Detect which shape we are
        // in by asking whether the parent holds exactly one verse, and in the flat
        // case collect the siblings up to the next verse number instead.
        const nodesFor = n => {
            const scope = document.getElementById($4) || document;
            const el = scope.querySelector('[id="v-' + $2 + '-' + $3 + '-' + n + '"]');
            if (!el) return [];
            const parent = el.parentElement;
            if (parent && parent.querySelectorAll('[id^="v-"]').length === 1) return [parent];
            const run = [el];
            for (let s = el.nextSibling; s; s = s.nextSibling) {
                if (s.nodeType !== 1) continue;
                if (s.id && s.id.lastIndexOf('v-', 0) === 0) break;
                run.push(s);
            }
            return run;
        };

        let current = null;
        const clear = () => {
            if (current === null) return;
            nodesFor(current).forEach(el => el.classList.remove('cr-audio-on'));
            current = null;
        };

        // Exactly one player speaks at a time, across every column on the page.
        audio.addEventListener('play', () => {
            document.dispatchEvent(new CustomEvent('cr-audio-play', { detail: audio }));
        });
        document.addEventListener('cr-audio-play', e => {
            if (e.detail === audio) return;
            if (!audio.paused) audio.pause();
            clear();
        });

        audio.addEventListener('timeupdate', () => {
            if (audio.duration) fill.style.width = (audio.currentTime / audio.duration * 100) + '%';
            if (!marks || !marks.length) return;
            const t = audio.currentTime * 1000;
            let found = null;
            for (let i = 0; i < marks.length; i++) {
                if (marks[i][1] <= t) found = marks[i][0]; else break;
            }
            if (found === current) return;
            clear();
            current = found;
            const run = nodesFor(found);
            run.forEach(el => el.classList.add('cr-audio-on'));
            if (run.length) run[0].scrollIntoView({ block: 'center', behavior: 'smooth' });
        });
        audio.addEventListener('ended', () => {
            clear();
            const scope = document.getElementById($4) || document;
            const nextChapter = String(Number($3) + 1);
            const nextHost = scope.querySelector(
                '.cr-audio-player[data-book="' + $2 + '"][data-chapter="' + nextChapter + '"]');
            // No next player on the page (end of book, or a mode that only
            // renders one chapter at a time) — stop quietly rather than guess.
            if (!nextHost) return;
            const nextButton = nextHost.querySelector('vaadin-button, button');
            if (nextButton) nextButton.click();
        });
        audio.play().catch(() => {});
        """;

    /**
     * @param aMp3Url       public URL of the chapter mp3
     * @param anOffsetsUrl   its companion per-verse offsets JSON
     * @param aBookCode     book code as used in the verse ids, e.g. GEN
     * @param aChapter      chapter number as used in the verse ids
     * @param aColumnRootId DOM id of this column's scroll root ({@code col-<uid>}),
     *                      without which the wrong column would highlight
     * @param aTooltip      accessible label for the button
     */
    public ChapterAudioPlayer(final String aMp3Url, final String anOffsetsUrl,
                              final String aBookCode, final int aChapter,
                              final String aColumnRootId, final String aTooltip) {
        // Present on every player from render, not just ones a reader has
        // clicked — the 'ended' handler below needs to find the NEXT
        // chapter's player even when that chapter has never been played.
        addClassName("cr-audio-player");
        getElement().setAttribute("data-book", aBookCode);
        getElement().setAttribute("data-chapter", String.valueOf(aChapter));
        final Button play = new Button(VaadinIcon.PLAY_CIRCLE_O.create());
        final String label = aTooltip == null ? "" : aTooltip;
        play.getElement().setAttribute("title", label);
        play.getElement().setAttribute("aria-label", label);
        play.getStyle().set("min-width", "0").set("padding", "0")
                       .set("color", "var(--lumo-secondary-text-color)");
        play.addClickListener(e -> getElement().executeJs(
                JS, aMp3Url, anOffsetsUrl, aBookCode, String.valueOf(aChapter),
                aColumnRootId == null ? "" : aColumnRootId, HIGHLIGHT_CSS));
        add(play);
    }
}
