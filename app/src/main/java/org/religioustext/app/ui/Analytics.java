// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;

/**
 * Sends custom Umami events from server-side UI code.
 *
 * <p>Property NAMES are our own constants and are written into the script text, so
 * they are checked against a strict identifier pattern; every VALUE travels as a bound
 * {@code executeJs} parameter and is never spliced into script. A property whose value
 * is {@code null} is left out, so a caller can pass an optional value without a branch.
 *
 * <p>Every call is a silent no-op where {@code window.umami} is not defined — local and
 * dev runs, a visitor who has opted out, a blocked script.
 *
 * <p>Four delivery shapes:
 * <ul>
 *   <li>{@link #track}: send now.</li>
 *   <li>{@link #trackWhenReady}: send now, or as soon as the tracker has loaded — for an
 *       event raised while a page is still loading (a failed login, a verified email),
 *       when the tracker's script may not have arrived yet.</li>
 *   <li>{@link #trackDebounced}: send once things have settled — for an action that
 *       repeats while a person types, so one burst counts once.</li>
 *   <li>{@link #queueForNextPage}: stash the event in {@code sessionStorage} for
 *       {@link #REPLAY_SCRIPT} to send after the next page load — for an action that
 *       reloads the page, where an event sent just before would be lost as the page
 *       unloads.</li>
 * </ul>
 *
 * <p>{@code executeJs} takes {@code Serializable...}; the parameters are therefore
 * built as a {@code Serializable[]}. An {@code Object[]} is itself {@code Serializable}
 * and would be sent as ONE parameter, failing to encode.
 */
public final class Analytics {

    /** The {@code sessionStorage} key an event waits under across a page reload. */
    public static final String PENDING_KEY = "cr.pendingEvent";

    /**
     * Inline script that sends a queued event once the Umami tracker has loaded, polling
     * for up to ten seconds. Emitted by the app shell right after the tracker's own tag.
     *
     * <p>It must contain no {@code &}, {@code <} or {@code >} inside the script body: the
     * app shell parses inline content as HTML and writes it back out escaped, so an
     * {@code &&} reaches the browser as {@code &amp;&amp;} — a syntax error on every page
     * load. Hence {@code tries++ !== 50} rather than a comparison, and no {@code &&}.
     */
    public static final String REPLAY_SCRIPT =
        "<script>(function(){try{var raw=sessionStorage.getItem('" + PENDING_KEY + "');"
        + "if(!raw)return;sessionStorage.removeItem('" + PENDING_KEY + "');"
        + "var ev=JSON.parse(raw),tries=0;(function send(){"
        + "if(window.umami){window.umami.track(ev.name,ev.data);return;}"
        + "if(tries++!==50){setTimeout(send,200);}})();}catch(e){}})();</script>";

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** A script and the parameters it expects, ready for {@code executeJs}. */
    public record Call(String script, Serializable[] params) { }

    private Analytics() { }

    /** Send one event now, from the given component's element. */
    public static void track(final Component anOwner, final String aName, final Serializable... aKeyValues) {
        run(anOwner, build(aName, aKeyValues));
    }

    /**
     * Send one event as soon as {@code window.umami} exists, polling for up to ten seconds.
     * For an event raised during page load, when the tracker's own script may still be
     * on its way; {@link #track} would be a silent no-op in that window.
     */
    public static void trackWhenReady(final UI aUi, final String aName, final Serializable... aKeyValues) {
        final Call c = buildWhenReady(aName, aKeyValues);
        aUi.getPage().executeJs(c.script(), c.params());
    }

    /** As {@link #trackWhenReady(UI, String, Serializable...)}, from a component's element. */
    public static void trackWhenReady(final Component anOwner, final String aName, final Serializable... aKeyValues) {
        run(anOwner, buildWhenReady(aName, aKeyValues));
    }

    /**
     * Send one event after {@code aDelayMs} of quiet. A later call with the same
     * {@code aTimerKey} replaces the pending one, so only the last in a burst is sent.
     */
    public static void trackDebounced(final Component anOwner, final String aTimerKey, final int aDelayMs,
                                      final String aName, final Serializable... aKeyValues) {
        run(anOwner, buildDebounced(aTimerKey, aDelayMs, aName, aKeyValues));
    }

    static Call build(final String aName, final Serializable... aKeyValues) {
        final Parts p = parts(0, aName, aKeyValues);
        return new Call("window.umami && window.umami.track($0, " + p.object + ")", p.values);
    }

    static Call buildWhenReady(final String aName, final Serializable... aKeyValues) {
        final Parts p = parts(0, aName, aKeyValues);
        return new Call("(function send(n) { if (window.umami) { window.umami.track($0, " + p.object
            + "); } else if (n !== 50) { setTimeout(function () { send(n + 1); }, 200); } })(0);", p.values);
    }

    static Call buildDebounced(final String aTimerKey, final int aDelayMs, final String aName,
                               final Serializable... aKeyValues) {
        requireIdentifier(aTimerKey);
        final Call send = build(aName, aKeyValues);
        final String script = "window.__crDeb = window.__crDeb || {};"
            + " clearTimeout(window.__crDeb." + aTimerKey + ");"
            + " window.__crDeb." + aTimerKey + " = setTimeout(function () { " + send.script() + "; }, "
            + Math.max(0, aDelayMs) + ");";
        return new Call(script, send.params());
    }

    /**
     * A script fragment that queues an event for the next page load. Its parameters start
     * at {@code $aFirstParam}, so it can be placed in front of a script that already uses
     * {@code $0..}; append {@link Call#params()} after that script's own parameters.
     */
    public static Call queueForNextPage(final int aFirstParam, final String aName,
                                        final Serializable... aKeyValues) {
        final Parts p = parts(aFirstParam, aName, aKeyValues);
        return new Call("try { sessionStorage.setItem('" + PENDING_KEY + "', JSON.stringify({name: $"
            + aFirstParam + ", data: " + p.object + "})); } catch (e) { } ", p.values);
    }

    // ── internals ────────────────────────────────────────────────────────

    private record Parts(String object, Serializable[] values) { }

    /** {@code {k1: $n, k2: $n+1}} for the non-null pairs, and the values to bind, name first. */
    private static Parts parts(final int aFirstParam, final String aName, final Serializable[] aKeyValues) {
        final List<Serializable> values = new ArrayList<>();
        values.add(aName);
        final StringBuilder obj = new StringBuilder("{");
        for (int i = 0; i + 1 < aKeyValues.length; i += 2) {
            final Serializable key = aKeyValues[i];
            requireIdentifier(String.valueOf(key));
            if (aKeyValues[i + 1] == null) continue;           // a property with no value is left out
            if (values.size() > 1) obj.append(", ");
            obj.append(key).append(": $").append(aFirstParam + values.size());
            values.add(aKeyValues[i + 1]);
        }
        obj.append('}');
        return new Parts(obj.toString(), values.toArray(new Serializable[0]));
    }

    private static void requireIdentifier(final String aKey) {
        if (aKey == null || !IDENTIFIER.matcher(aKey).matches())
            throw new IllegalArgumentException("not a usable analytics property name: " + aKey);
    }

    private static void run(final Component anOwner, final Call aCall) {
        anOwner.getElement().executeJs(aCall.script(), aCall.params());
    }
}
