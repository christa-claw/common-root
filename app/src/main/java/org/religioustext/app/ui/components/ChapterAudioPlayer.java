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
        // The host div shrink-wraps to the play button (~24px), so a plain
        // width:100% on the audio resolves to 24px of unusable player. The
        // chapter-heading row it sits in is already flex-wrap:wrap, so taking a
        // full flex line drops the player onto its own row at the column's full
        // width and leaves the heading beside the button untouched. Set here
        // rather than in Java: until the reader presses play there is no player,
        // and the bare button should stay inline next to the chapter title.
        host.style.flexBasis = '100%';
        host.style.width = '100%';
        host.appendChild(audio);
        host._crAudio = audio;

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
        audio.addEventListener('ended', clear);
        audio.play().catch(() => {});
        """;

    /**
     * @param aMp3Url       public URL of the chapter mp3
     * @param aOffsetsUrl   its companion per-verse offsets JSON
     * @param aBookCode     book code as used in the verse ids, e.g. GEN
     * @param aChapter      chapter number as used in the verse ids
     * @param aColumnRootId DOM id of this column's scroll root ({@code col-<uid>}),
     *                      without which the wrong column would highlight
     * @param aTooltip      accessible label for the button
     */
    public ChapterAudioPlayer(final String aMp3Url, final String aOffsetsUrl,
                              final String aBookCode, final int aChapter,
                              final String aColumnRootId, final String aTooltip) {
        final Button play = new Button(VaadinIcon.PLAY_CIRCLE_O.create());
        final String label = aTooltip == null ? "" : aTooltip;
        play.getElement().setAttribute("title", label);
        play.getElement().setAttribute("aria-label", label);
        play.getStyle().set("min-width", "0").set("padding", "0")
                       .set("color", "var(--lumo-secondary-text-color)");
        play.addClickListener(e -> getElement().executeJs(
                JS, aMp3Url, aOffsetsUrl, aBookCode, String.valueOf(aChapter),
                aColumnRootId == null ? "" : aColumnRootId, HIGHLIGHT_CSS));
        add(play);
    }
}
