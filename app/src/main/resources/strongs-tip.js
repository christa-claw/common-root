// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
//
// The Strong's tooltip (docs/strongs-design.md). Installed once per page by StrongsTip.install().
// A number is <span class="strongs-n" data-s="H430" data-l="fi">. Hovering it, focusing it, or
// tapping it fetches the entry from /lexicon/hebrew|greek/{id}?lang=... (H or G) (cached in memory) and shows ONE
// shared tooltip, #strongs-tip, fixed to the viewport so no scroll container can clip it.
//   - mouse: shown on hover, hidden when the pointer leaves;
//   - touch / click: a tap pins it open; a second tap, a tap elsewhere or Escape closes it.
// Text is set with textContent only: nothing from the server is ever parsed as HTML.
(function () {
  if (window.__strongsTip) return;
  window.__strongsTip = true;

  var cache = {}, tip = null, current = null, pinned = null, looked = {}, failed = {};

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text) e.textContent = text;
    return e;
  }
  function target(ev) {
    var t = ev.target;
    return t && t.closest ? t.closest('.strongs-n') : null;
  }
  function ensure() {
    if (!tip) {
      tip = el('div');
      tip.id = 'strongs-tip';
      tip.setAttribute('role', 'tooltip');
      document.body.appendChild(tip);
    }
    return tip;
  }
  function entry(id, lang) {
    var key = lang + '|' + id;
    if (cache[key]) return Promise.resolve(cache[key]);
    return fetch('/lexicon/' + (id.charAt(0) === 'G' ? 'greek' : 'hebrew') + '/' + encodeURIComponent(id) + '?lang=' + encodeURIComponent(lang))
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (j) { if (j) cache[key] = j; return j; })
      .catch(function () { return null; });
  }
  function clip(s, n) {
    if (!s || s.length <= n) return s || '';
    var cut = s.slice(0, n), sp = cut.lastIndexOf(' ');
    return (sp > n * 0.6 ? cut.slice(0, sp) : cut) + '…';
  }
  function render(j) {
    var t = ensure();
    while (t.firstChild) t.removeChild(t.firstChild);
    t.setAttribute('dir', j.dir === 'rtl' ? 'rtl' : 'ltr');
    var head = el('div');
    var lemma = el('span', 'st-lemma', j.lemma);
    lemma.setAttribute('lang', j.lemmaLang || 'he');
    lemma.setAttribute('dir', j.lemmaDir || 'rtl');
    head.appendChild(lemma);
    head.appendChild(el('span', 'st-x', j.xlit));
    head.appendChild(el('span', 'st-n', j.strongs));
    t.appendChild(head);
    if (j.gloss) t.appendChild(el('div', 'st-g', j.gloss));
    if (j.definition) t.appendChild(el('div', 'st-d', clip(j.definition, 300)));
    if (j.hint) t.appendChild(el('div', 'st-h', j.hint));
    var foot = el('div', 'st-f');
    if (j.note) foot.appendChild(el('span', 'st-m', j.note + ' · '));
    if (j.credit && /^https:\/\//.test(j.credit.url)) {
      var a = el('a', null, j.credit.text);
      a.href = j.credit.url;
      a.target = '_blank';
      a.rel = 'noopener';
      foot.appendChild(a);
    }
    t.appendChild(foot);
  }
  function place(anchor) {
    var t = ensure(), r = anchor.getBoundingClientRect();
    t.style.visibility = 'hidden';
    t.style.display = 'block';
    var w = t.offsetWidth, h = t.offsetHeight;
    var x = Math.min(Math.max(8, r.left + r.width / 2 - w / 2), window.innerWidth - w - 8);
    var y = r.bottom + 6;
    if (y + h > window.innerHeight - 8) y = Math.max(8, r.top - h - 6);
    t.style.left = x + 'px';
    t.style.top = y + 'px';
    t.style.visibility = 'visible';
  }
  // Analytics: one "strongs-lookup" per number per page load, so a pointer sweeping back and
  // forth over the same word counts once. The full window reports its own lookups from the
  // server (ReaderView.openStrongs), with via=window. No-op when Umami is not installed.
  function track(id, passage) {
    if (!id || looked[id] || !window.umami) return;
    looked[id] = true;
    var data = { strongs: id, via: 'tooltip' };
    if (passage) data.source = passage;      // the verse the number sits in ("Genesis 1:1")
    window.umami.track('strongs-lookup', data);
  }
  // Health: the entry could not be fetched (the lexicon route is down or the number unknown), so the
  // tooltip never appears. Once per number per page load, like the lookups.
  function fail(id) {
    if (!id || failed[id] || !window.umami) return;
    failed[id] = true;
    window.umami.track('strongs-lookup-failed', { strongs: id });
  }
  function show(anchor) {
    current = anchor;
    entry(anchor.getAttribute('data-s'), anchor.getAttribute('data-l') || 'en').then(function (j) {
      if (!j) { fail(anchor.getAttribute('data-s')); return; }
      if (current !== anchor || !document.body.contains(anchor)) return;
      render(j);
      place(anchor);
      track(anchor.getAttribute('data-s'), anchor.getAttribute('data-p'));
    });
  }
  function hide() {
    current = null;
    pinned = null;
    if (tip) tip.style.display = 'none';
  }

  document.addEventListener('mouseover', function (ev) {
    var a = target(ev);
    if (a) { if (!pinned) show(a); return; }
    if (!pinned && tip && !tip.contains(ev.target)) hide();   // also clears a tooltip whose number vanished
  });
  document.addEventListener('focusin', function (ev) { var a = target(ev); if (a && !pinned) show(a); });
  document.addEventListener('focusout', function (ev) { if (target(ev) && !pinned) hide(); });
  document.addEventListener('click', function (ev) {
    var a = target(ev);
    if (a) {
      if (pinned === a) hide(); else { pinned = a; show(a); }
      return;
    }
    if (pinned && !(tip && tip.contains(ev.target))) hide();
  }, true);
  // Double-click (double-tap) opens the full window: the page host (the reader view, handed over by
  // StrongsTip.install) opens the dialog on the server. The two clicks pin then unpin the tooltip.
  document.addEventListener('dblclick', function (ev) {
    var a = target(ev);
    if (!a) return;
    ev.preventDefault();
    hide();
    var host = window.__strongsHost;
    if (host && host.$server && host.$server.openStrongs) host.$server.openStrongs(a.getAttribute('data-s'), a.getAttribute('data-col') || '', a.getAttribute('data-p') || '');
  });
  document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') hide(); });
  // The reader adjusts scroll positions on its own, so scroll events are frequent: follow the
  // number instead of closing, and close only when it has scrolled out of view.
  var pending = false;
  window.addEventListener('scroll', function () {
    if (!current || pending) return;
    pending = true;
    window.requestAnimationFrame(function () {
      pending = false;
      if (!current || !document.body.contains(current)) { hide(); return; }
      var r = current.getBoundingClientRect();
      if (r.bottom < 0 || r.top > window.innerHeight) hide(); else place(current);
    });
  }, true);
  window.addEventListener('resize', hide);
})();
