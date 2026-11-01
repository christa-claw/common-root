// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
//
// Arrow-key navigation in the ACTIVE column (the one last clicked, else the first). Installed once
// per page by ReaderKeys.install().
//   Left / Right : previous / next chapter  (server: ReaderView.stepChapterKey, so sync, book
//                  boundaries and window loading behave exactly like the nav-bar chevrons)
//   Up / Down    : previous / next verse    (client: moves a verse cursor, scrolls to it)
// Audio follows: a chapter step while a chapter is playing starts the new chapter's player; a verse
// step while a player is running seeks it to that verse's offset (ChapterAudioPlayer publishes
// host._crSeekVerse once its offsets JSON has loaded). Playback never starts from a key press alone.
// Keys are ignored while typing, in menus/dialogs, on the audio controls, or with a modifier held.
(function () {
  if (window.__readerKeys) return;
  window.__readerKeys = true;

  var activeId = null;

  var st = document.createElement('style');
  st.textContent =
    '.cr-col-active { box-shadow: inset 0 2px 0 var(--lumo-primary-color); }' +
    '.cr-verse-cursor { background: var(--lumo-contrast-10pct); border-radius: 3px;' +
    ' -webkit-box-decoration-break: clone; box-decoration-break: clone; }';
  document.head.appendChild(st);

  function columns() { return Array.prototype.slice.call(document.querySelectorAll('[id^="col-"]')); }

  function setActive(col) {
    if (!col) return;
    activeId = col.id;
    columns().forEach(function (c) { c.classList.toggle('cr-col-active', c === col && columns().length > 1); });
  }
  function activeCol() {
    var cols = columns();
    for (var i = 0; i < cols.length; i++) if (cols[i].id === activeId) return cols[i];
    return cols[0] || null;
  }
  function colOf(node) { return node && node.closest ? node.closest('[id^="col-"]') : null; }

  document.addEventListener('pointerdown', function (e) { setActive(colOf(e.target)); }, true);
  document.addEventListener('focusin', function (e) { var c = colOf(e.target); if (c) setActive(c); }, true);

  var BLOCK = 'input,textarea,select,audio,[contenteditable="true"],' +
    'vaadin-text-field,vaadin-text-area,vaadin-select,vaadin-combo-box,vaadin-integer-field,' +
    'vaadin-dialog-overlay,vaadin-overlay,vaadin-context-menu-overlay,vaadin-select-overlay';
  function typing(e) {
    var path = e.composedPath ? e.composedPath() : [e.target];
    for (var i = 0; i < path.length; i++) {
      var n = path[i];
      if (n && n.matches && n.matches(BLOCK)) return true;
    }
    return false;
  }

  // ── verses ────────────────────────────────────────────────────────────────
  var VID = /^v-(.+)-(\d+)-(\d+)$/;
  function verses(col) { return Array.prototype.slice.call(col.querySelectorAll('[id^="v-"]')).filter(function (e) { return VID.test(e.id); }); }

  // Same two DOM shapes ChapterAudioPlayer handles: a per-verse wrapper, or a flat inline run.
  function nodesFor(el) {
    var parent = el.parentElement;
    if (parent && parent.querySelectorAll('[id^="v-"]').length === 1) return [parent];
    var run = [el];
    for (var s = el.nextSibling; s; s = s.nextSibling) {
      if (s.nodeType !== 1) continue;
      if (s.id && s.id.lastIndexOf('v-', 0) === 0) break;
      run.push(s);
    }
    return run;
  }
  function clearCursor(col) {
    col.querySelectorAll('.cr-verse-cursor').forEach(function (n) { n.classList.remove('cr-verse-cursor'); });
  }

  // Where "the current verse" is: the cursor, else the verse being read aloud, else the first verse
  // that is on screen below the sticky header.
  function currentVerse(col, list) {
    var id = col._crCursorId;
    if (id) { var c = list.filter(function (e) { return e.id === id; })[0]; if (c) return c; }
    var on = col.querySelector('.cr-audio-on');
    if (on) {
      var v = on.id && VID.test(on.id) ? on : on.querySelector('[id^="v-"]');
      if (v) return v;
    }
    var top = col.getBoundingClientRect().top + (col.firstElementChild ? col.firstElementChild.getBoundingClientRect().height : 0);
    for (var i = 0; i < list.length; i++) if (list[i].getBoundingClientRect().top >= top) return list[i];
    return list[0] || null;
  }

  function playerFor(col, book, chapter) {
    return col.querySelector('.cr-audio-player[data-book="' + book + '"][data-chapter="' + chapter + '"]');
  }
  function runningPlayer(col) {
    var hs = col.querySelectorAll('.cr-audio-player');
    for (var i = 0; i < hs.length; i++) if (hs[i]._crAudio && !hs[i]._crAudio.paused) return hs[i];
    return null;
  }
  function press(host) { var b = host.querySelector('vaadin-button, button'); if (b) b.click(); }
  function poll(fn, tries) {
    var n = 0;
    (function again() { if (fn() || ++n >= tries) return; setTimeout(again, 100); })();
  }

  function goVerse(col, delta) {
    var list = verses(col);
    if (!list.length) return;
    var cur = currentVerse(col, list);
    var at = cur ? list.indexOf(cur) : -1;
    var next = list[at + delta];
    if (!next) { stepChapter(col, delta); return; }       // off the rendered window: fall back to a chapter step
    var m = VID.exec(next.id);
    clearCursor(col);
    nodesFor(next).forEach(function (n) { n.classList.add('cr-verse-cursor'); });
    col._crCursorId = next.id;
    next.scrollIntoView({ block: 'center', behavior: 'smooth' });

    var playing = runningPlayer(col);
    if (!playing) return;
    var book = m[1], chapter = m[2], verse = Number(m[3]);
    var target = playerFor(col, book, chapter);
    if (!target) return;
    if (target !== playing) press(target);                  // crossed into another chapter: its player takes over
    poll(function () {
      if (!target._crSeekVerse) return false;
      target._crSeekVerse(verse);
      return true;
    }, 30);
  }

  // ── chapters ──────────────────────────────────────────────────────────────
  function stepChapter(col, delta) {
    var host = window.__readerKeysHost;
    if (!host || !host.$server) return;
    var wasPlaying = !!runningPlayer(col);
    col._crCursorId = null;
    clearCursor(col);
    host.$server.stepChapterKey(col.id.replace('col-', ''), delta).then(function (where) {
      if (!wasPlaying || !where) return;
      var p = where.split('|');
      poll(function () {
        var t = playerFor(col, p[0], p[1]);
        if (!t) return false;
        if (!t._crAudio || t._crAudio.paused) press(t);
        return true;
      }, 30);
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || e.shiftKey) return;
    var k = e.key;
    if (k !== 'ArrowLeft' && k !== 'ArrowRight' && k !== 'ArrowUp' && k !== 'ArrowDown') return;
    if (typing(e)) return;
    var col = activeCol();
    if (!col) return;
    e.preventDefault();
    if (k === 'ArrowLeft' || k === 'ArrowRight') stepChapter(col, k === 'ArrowRight' ? 1 : -1);
    else goVerse(col, k === 'ArrowDown' ? 1 : -1);
  });
})();
