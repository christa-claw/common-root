// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * Play control for one narrated section of the About page.
 *
 * <p>Deliberately the simple sibling of {@link ChapterAudioPlayer}: there is
 * no verse structure to sync against here, so no offsets fetch, no
 * highlighting, and no auto-advance to a "next chapter" — just a button that
 * lazily builds a plain {@code <audio controls>} on first click, exactly like
 * {@code ChapterAudioPlayer} does, so a page with several sections costs
 * several buttons, not several megabytes.
 *
 * <p>Joins the SAME {@code cr-audio-play} event bus {@code ChapterAudioPlayer}
 * uses, so starting a section here pauses a chapter playing in the reader (and
 * vice versa) rather than talking over it — the two features share one
 * "only one voice at a time" rule even though they are otherwise independent.
 */
public class SectionAudioPlayer extends Div {

    private static final String JS = """
        const host = this;
        if (host._crAudio) {
            host._crAudio.paused ? host._crAudio.play() : host._crAudio.pause();
            return;
        }
        const audio = document.createElement('audio');
        audio.controls = true;
        audio.preload = 'none';
        audio.src = $0;
        audio.style.width = '100%';
        audio.style.maxWidth = '420px';
        audio.style.marginTop = '8px';
        audio.style.display = 'block';
        audio.style.setProperty('accent-color', 'var(--lumo-primary-color)');
        host.appendChild(audio);
        host._crAudio = audio;

        const caption = document.createElement('div');
        caption.style.fontSize = 'var(--lumo-font-size-xxs)';
        caption.style.color = 'var(--lumo-secondary-text-color)';
        caption.style.marginTop = '2px';
        caption.textContent = 'Audio generated with Azure AI';
        host.appendChild(caption);

        audio.addEventListener('play', () => {
            document.dispatchEvent(new CustomEvent('cr-audio-play', { detail: audio }));
        });
        document.addEventListener('cr-audio-play', e => {
            if (e.detail === audio) return;
            if (!audio.paused) audio.pause();
        });

        audio.play().catch(() => {});
        """;

    /**
     * @param aMp3Url  public URL of the section's mp3
     * @param aTooltip accessible label for the button (the localized "Listen")
     */
    public SectionAudioPlayer(final String aMp3Url, final String aTooltip) {
        this(aMp3Url, aTooltip, null);
    }

    /**
     * @param aMp3Url    public URL of the section's mp3
     * @param aTooltip   accessible label for the button (the localized "Listen")
     * @param anIconColor CSS color for the icon, or null for the default
     *                    secondary text color — the hero banner's dark
     *                    background needs white, every other section sits on
     *                    a light background and wants the ordinary muted tone.
     */
    public SectionAudioPlayer(final String aMp3Url, final String aTooltip, final String anIconColor) {
        addClassName("cr-about-audio-player");
        getStyle().set("display", "inline-block");
        final Button play = new Button(VaadinIcon.VOLUME_UP.create());
        final String label = aTooltip == null ? "" : aTooltip;
        play.getElement().setAttribute("title", label);
        play.getElement().setAttribute("aria-label", label);
        play.getStyle().set("min-width", "0").set("padding", "0")
                       .set("color", anIconColor == null ? "var(--lumo-secondary-text-color)" : anIconColor);
        play.addClickListener(e -> getElement().executeJs(JS, aMp3Url));
        add(play);
    }
}
