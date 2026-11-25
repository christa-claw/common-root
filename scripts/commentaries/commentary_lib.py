# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
commentary_lib.py — the shared half of the commentary pipeline.

Every source format (Sefaria JSON, CrossWire SWORD modules, CCEL ThML) has its
own reader in this directory. Each reader produces the same in-memory model
defined here, and this module turns that model into ONE religious-text.xsd
(v1.5) <text type="commentary"> document per work and language, validates it,
and loads it into BaseX.

  book    = the SCRIPTURE book commented on, with the USFM code every Bible in
            the corpus uses, so a commentary column syncs against any Bible.
  verse   = the verse a comment is anchored at; it holds one <comment> per
            comment the source gives there, in source order.
  comment = one or more <p>; inline <em>, <strong>, <note>, <ref target>.
  intro   = book- or chapter-level material (a chapter outline, a preface).

Design notes and the run book are in docs/commentary-pipeline.md.
No third-party dependencies — stdlib only. xmllint (macOS ships it) validates.
"""
from __future__ import annotations

import datetime as _dt
import html
import json
import re
import subprocess
import tempfile
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr

REPO = Path(__file__).resolve().parents[2]
SCHEMA = REPO / "schema" / "religious-text.xsd"
DATA = Path(__file__).resolve().parent / "data"
NS = "http://religioustext.org/schema/1.0"

# ── Books ─────────────────────────────────────────────────────────────────
# (USFM code, English name, OSIS id, testament, extra aliases)
# Order = canonical order written to book/@canonicalOrder (66 books, then the
# deuterocanon in the order the DRA edition already uses).
BOOKS = [
    ("GEN", "Genesis", "Gen", "OT", "ge gn bereshit bereishit bereshith"),
    ("EXO", "Exodus", "Exod", "OT", "ex exo shemot shemos"),
    ("LEV", "Leviticus", "Lev", "OT", "le lv vayikra"), ("NUM", "Numbers", "Num", "OT", "nu nm nb bamidbar bemidbar"),
    ("DEU", "Deuteronomy", "Deut", "OT", "de dt devarim"), ("JOS", "Joshua", "Josh", "OT", "jos jsh"),
    ("JDG", "Judges", "Judg", "OT", "jdg jg jdgs"), ("RUT", "Ruth", "Ruth", "OT", "ru rth"),
    ("1SA", "1 Samuel", "1Sam", "OT", "1sa 1sm 1kingdoms"), ("2SA", "2 Samuel", "2Sam", "OT", "2sa 2sm"),
    ("1KI", "1 Kings", "1Kgs", "OT", "1ki 1kg"), ("2KI", "2 Kings", "2Kgs", "OT", "2ki 2kg"),
    ("1CH", "1 Chronicles", "1Chr", "OT", "1ch 1chron"), ("2CH", "2 Chronicles", "2Chr", "OT", "2ch 2chron"),
    ("EZR", "Ezra", "Ezra", "OT", "ezr"), ("NEH", "Nehemiah", "Neh", "OT", "ne"),
    ("EST", "Esther", "Esth", "OT", "es est"), ("JOB", "Job", "Job", "OT", "jb"),
    ("PSA", "Psalms", "Ps", "OT", "psalm ps psa pss"), ("PRO", "Proverbs", "Prov", "OT", "pr prv"),
    ("ECC", "Ecclesiastes", "Eccl", "OT", "ec ecc qoh qoheleth kohelet"),
    ("SNG", "Song of Songs", "Song", "OT", "songofsolomon canticles cant sos so sg sng shirhashirim"),
    ("ISA", "Isaiah", "Isa", "OT", "is"), ("JER", "Jeremiah", "Jer", "OT", "je jr"),
    ("LAM", "Lamentations", "Lam", "OT", "la eichah"), ("EZK", "Ezekiel", "Ezek", "OT", "eze ezk"),
    ("DAN", "Daniel", "Dan", "OT", "da dn"), ("HOS", "Hosea", "Hos", "OT", "ho"),
    ("JOL", "Joel", "Joel", "OT", "jl joe"), ("AMO", "Amos", "Amos", "OT", "am"),
    ("OBA", "Obadiah", "Obad", "OT", "ob"), ("JON", "Jonah", "Jonah", "OT", "jnh jon"),
    ("MIC", "Micah", "Mic", "OT", "mi"), ("NAM", "Nahum", "Nah", "OT", "na"),
    ("HAB", "Habakkuk", "Hab", "OT", "hb"), ("ZEP", "Zephaniah", "Zeph", "OT", "zp"),
    ("HAG", "Haggai", "Hag", "OT", "hg"), ("ZEC", "Zechariah", "Zech", "OT", "zc"),
    ("MAL", "Malachi", "Mal", "OT", "ml"),
    ("MAT", "Matthew", "Matt", "NT", "mt mat"), ("MRK", "Mark", "Mark", "NT", "mk mr mrk"),
    ("LUK", "Luke", "Luke", "NT", "lk lu"), ("JHN", "John", "John", "NT", "jn jhn joh"),
    ("ACT", "Acts", "Acts", "NT", "ac act"), ("ROM", "Romans", "Rom", "NT", "ro rm"),
    ("1CO", "1 Corinthians", "1Cor", "NT", "1co"), ("2CO", "2 Corinthians", "2Cor", "NT", "2co"),
    ("GAL", "Galatians", "Gal", "NT", "ga"), ("EPH", "Ephesians", "Eph", "NT", "ep"),
    ("PHP", "Philippians", "Phil", "NT", "php pp"), ("COL", "Colossians", "Col", "NT", "co"),
    ("1TH", "1 Thessalonians", "1Thess", "NT", "1th"), ("2TH", "2 Thessalonians", "2Thess", "NT", "2th"),
    ("1TI", "1 Timothy", "1Tim", "NT", "1ti 1tm"), ("2TI", "2 Timothy", "2Tim", "NT", "2ti 2tm"),
    ("TIT", "Titus", "Titus", "NT", "tit"), ("PHM", "Philemon", "Phlm", "NT", "phm phile"),
    ("HEB", "Hebrews", "Heb", "NT", "he"), ("JAS", "James", "Jas", "NT", "jas jm"),
    ("1PE", "1 Peter", "1Pet", "NT", "1pe 1pt"), ("2PE", "2 Peter", "2Pet", "NT", "2pe 2pt"),
    ("1JN", "1 John", "1John", "NT", "1jn 1jo"), ("2JN", "2 John", "2John", "NT", "2jn 2jo"),
    ("3JN", "3 John", "3John", "NT", "3jn 3jo"), ("JUD", "Jude", "Jude", "NT", "jud jde"),
    ("REV", "Revelation", "Rev", "NT", "re rv apocalypse revelationofjohn"),
    ("TOB", "Tobit", "Tob", "DC", "tb"), ("JDT", "Judith", "Jdt", "DC", "jdth"),
    ("ESG", "Esther (Greek)", "AddEsth", "DC", "addesth"), ("WIS", "Wisdom", "Wis", "DC", "wisdomofsolomon"),
    ("SIR", "Sirach", "Sir", "DC", "ecclesiasticus ecclus"), ("BAR", "Baruch", "Bar", "DC", ""),
    ("LJE", "Letter of Jeremiah", "EpJer", "DC", "epjer"), ("S3Y", "Song of the Three", "PrAzar", "DC", "prazar"),
    ("SUS", "Susanna", "Sus", "DC", ""), ("BEL", "Bel and the Dragon", "Bel", "DC", ""),
    ("1MA", "1 Maccabees", "1Macc", "DC", "1mac 1ma"), ("2MA", "2 Maccabees", "2Macc", "DC", "2mac 2ma"),
    ("1ES", "1 Esdras", "1Esd", "DC", "3esdras"), ("2ES", "2 Esdras", "2Esd", "DC", "4esdras"),
    ("MAN", "Prayer of Manasseh", "PrMan", "DC", "prman"),
]
ORDER = {b[0]: i + 1 for i, b in enumerate(BOOKS)}
NAME = {b[0]: b[1] for b in BOOKS}
TESTAMENT = {b[0]: b[3] for b in BOOKS}
OSIS_TO_USFM = {b[2]: b[0] for b in BOOKS}
OSIS_TO_USFM.update({"1Kgdms": "1SA", "2Kgdms": "2SA", "3Kgdms": "1KI", "4Kgdms": "2KI"})


def _norm(s: str) -> str:
    s = s.lower().replace("first ", "1").replace("second ", "2").replace("third ", "3")
    s = re.sub(r"^(i{1,3})\s+(?=[a-z])", lambda m: str(len(m.group(1))), s)  # "II Kings"
    return re.sub(r"[^0-9a-z]", "", s)


_ALIAS: dict[str, str] = {}
for _code, _name, _osis, _t, _extra in BOOKS:
    for _a in [_code, _name, _osis, *_extra.split()]:
        _ALIAS.setdefault(_norm(_a), _code)


def book_code(name: str) -> str | None:
    """Any English name, OSIS id, USFM code or common abbreviation -> USFM code."""
    if not name:
        return None
    n = _norm(name)
    if n in _ALIAS:
        return _ALIAS[n]
    # Unambiguous prefix of a full name ("Gene", "Deuter", "Philem")
    hits = {c for a, c in _ALIAS.items() if len(n) >= 3 and a.startswith(n)}
    return hits.pop() if len(hits) == 1 else None


_REF = re.compile(r"^\s*((?:[1-4]\s*)?[A-Za-z][A-Za-z .]*?)\.?\s*(\d+)(?:\s*[:.,]\s*(\d+)(?:\s*[-–]\s*(\d+))?)?\s*$")


def ref_target(text: str | None) -> str | None:
    """'Ge 1:1', 'Genesis 1:1-3', 'Gen.1.1', 'Bible:Gen.1.1', '|Gen|1|1|0|0' -> 'GEN.1.1' or None."""
    if not text:
        return None
    t = text.strip()
    t = re.sub(r"^[A-Za-z]+:", "", t) if re.match(r"^[A-Za-z]+:[0-9A-Z]", t) else t  # 'Bible:' / 'KJV:'
    if t.startswith("|"):                                              # ThML parsed="|Gen|1|1|0|0"
        parts = t.strip("|").split("|")
        code = book_code(parts[0]) if parts else None
        if not code or len(parts) < 2 or not parts[1].isdigit() or parts[1] == "0":
            return None
        out = f"{code}.{int(parts[1])}"
        if len(parts) > 2 and parts[2].isdigit() and parts[2] != "0":
            out += f".{int(parts[2])}"
            if len(parts) > 4 and parts[4].isdigit() and parts[4] not in ("0", parts[2]) and parts[3] in ("0", parts[1]):
                out += f"-{int(parts[4])}"
        return out
    t = t.split(";")[0].split(" ")[0] if re.match(r"^[0-9]?[A-Za-z]+\.\d", t) else t.split(";")[0]
    m = re.match(r"^([0-9]?[A-Za-z]+)\.(\d+)(?:\.(\d+))?(?:-[0-9A-Za-z]+\.(\d+)\.(\d+))?$", t)  # OSIS
    if m:
        code = OSIS_TO_USFM.get(m.group(1)) or book_code(m.group(1))
        if not code:
            return None
        out = f"{code}.{int(m.group(2))}"
        if m.group(3):
            out += f".{int(m.group(3))}"
            if m.group(5) and m.group(4) == m.group(2):
                out += f"-{int(m.group(5))}"
        return out
    m = _REF.match(t)
    if not m:
        return None
    code = book_code(m.group(1))
    if not code:
        return None
    out = f"{code}.{int(m.group(2))}"
    if m.group(3):
        out += f".{int(m.group(3))}"
        if m.group(4):
            out += f"-{int(m.group(4))}"
    return out


_LIST_TOKEN = re.compile(r"([;,])")
_T_FULL = re.compile(r"^((?:[1-4]\s?)?[A-Za-z][A-Za-z]*\.?)\s*(\d+)\s*[:.]\s*(\d+)(?:\s*[-–]\s*(\d+))?$")
_T_BOOKCH = re.compile(r"^((?:[1-4]\s?)?[A-Za-z][A-Za-z]*\.?)\s*(\d+)$")
_T_CHV = re.compile(r"^(\d+)\s*[:.]\s*(\d+)(?:\s*[-–]\s*(\d+))?$")
_T_V = re.compile(r"^(\d+)(?:\s*[-–]\s*(\d+))?$")


def linkify_list(text: str) -> str:
    """'Pr 8:22-24; 16:4; Mr 13:19' -> escaped XML with one <ref> per reference.
    A token without a book continues the previous one ('16:4'), a bare number
    continues the previous chapter ('9' in 'Ps 33:6,9'). Anything that does not
    parse stays plain text, so nothing printed is ever lost."""
    out, book, chap = [], None, None
    for tok in _LIST_TOKEN.split(text):
        if tok in (";", ","):
            out.append(tok)
            continue
        lead = tok[: len(tok) - len(tok.lstrip())]
        trail = tok[len(tok.rstrip()):]
        t = tok.strip()
        target = None
        m = _T_FULL.match(t)
        if m and book_code(m.group(1).rstrip(".")):
            book, chap = book_code(m.group(1).rstrip(".")), int(m.group(2))
            target = f"{book}.{chap}.{int(m.group(3))}" + (f"-{int(m.group(4))}" if m.group(4) else "")
        elif (m := _T_BOOKCH.match(t)) and book_code(m.group(1).rstrip(".")):
            book, chap = book_code(m.group(1).rstrip(".")), int(m.group(2))
            target = f"{book}.{chap}"
        elif (m := _T_CHV.match(t)) and book:
            chap = int(m.group(1))
            target = f"{book}.{chap}.{int(m.group(2))}" + (f"-{int(m.group(3))}" if m.group(3) else "")
        elif (m := _T_V.match(t)) and book and chap:
            target = f"{book}.{chap}.{int(m.group(1))}" + (f"-{int(m.group(2))}" if m.group(2) else "")
        else:
            book = book if t[:1].isdigit() else None   # unparsed words break the chain
        body = escape(clean_chars(t))
        out.append(lead + (f"<ref target={quoteattr(target)}>{body}</ref>" if target else body) + trail)
    return "".join(out)


# ── Versification (verse counts, for range ends and SWORD indexing) ────────
_V11N: dict | None = None


def versification(name: str = "kjv") -> dict:
    """SWORD canon tables: {'ot': [[name, osis, [verses per chapter]], ...], 'nt': [...]}.
    data/sword_versification.json was generated from the SWORD canon headers
    (via pysword 0.2.8's canons.py) — verse counts only."""
    global _V11N
    if _V11N is None:
        _V11N = json.loads((DATA / "sword_versification.json").read_text(encoding="utf-8"))
    return _V11N[name.lower()]


def chapter_lengths(code: str, v11n: str = "kjv") -> list[int]:
    for t in ("ot", "nt"):
        for _name, osis, lens in versification(v11n)[t]:
            if OSIS_TO_USFM.get(osis) == code:
                return lens
    return []


# ── Inline markup: HTML / ThML / OSIS fragment -> our <p> inline set ────────
VOID = {"br", "lb", "img", "hr", "milestone", "sync", "scripcom", "pb", "cb", "chapter", "verse",
        "div1start", "meta", "link", "input", "wbr", "col", "area", "base", "added-break", "index"}
BREAK = {"p", "div", "div1", "div2", "div3", "div4", "div5", "div6", "br", "lb", "l", "lg", "li", "item", "list",
         "tr", "table", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "title", "pre", "ul", "ol", "head"}
SKIP = {"script", "style", "sync", "scripcom", "rdg"}
EM = {"i", "em", "cite", "foreign", "transchange", "add", "q"}  # q: unwrap below
STRONG = {"b", "strong"}


_BAD_XML = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f￾￿]")


# Markup that a source escaped once too often, so it arrives as text: MHC has
# "(&lt;hi type="small-caps"&gt;b. c.&lt;/hi&gt; 4004.)". Only OSIS/ThML tag names, so
# a real "<" in prose is left alone.
_LITERAL_TAG = re.compile(r"</?(?:hi|foreign|note|reference|scripRef|title|lb|w|divineName|transChange|"
                          r"seg|milestone|catchWord|rdg)\b[^<>]{0,160}/?>", re.I)


def clean_chars(s: str) -> str:
    """Characters XML 1.0 forbids, and markup that leaked into the text. 0x15 is the
    old DOS code page's section sign, which several SWORD ThML modules still carry
    ("Ant. 1. iv. c. 7, \x15 3")."""
    return _LITERAL_TAG.sub("", _BAD_XML.sub("", s.replace("\x15", "§")))


class _Inline(HTMLParser):
    def __init__(self, resolve):
        super().__init__(convert_charrefs=True)
        self.resolve = resolve
        self.paras: list[str] = []
        self.cur: list[str] = []
        self.stack: list[str | None] = []   # emitted closing tag per open element (None = unwrapped)
        self.skip = 0
        self.capture: list[str] | None = None   # text of a multi-reference scripRef
        self.capture_passage = ""

    # what each open element emitted, so nesting stays balanced
    def _open_inline(self) -> int:
        return sum(1 for s in self.stack if s and s != "\x00capture")

    def _in(self, tag: str) -> bool:
        return f"</{tag}>" in self.stack

    def _break(self):
        if self.capture is not None:
            self.capture.append(" ")
            return
        if self._open_inline():
            self.cur.append(" ")
            return
        self._flush()

    def _flush(self):
        s = "".join(self.cur)
        self.cur = []
        s = re.sub(r"<(em|strong|note)>\s*</\1>", "", s)
        s = re.sub(r"\s+", " ", s).strip()
        if re.sub(r"<[^>]+>", "", s).strip():
            self.paras.append(s)

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        a = {k.lower(): (v or "") for k, v in attrs}
        cls = a.get("class", "")
        if tag in VOID or tag in ("br",):
            if not self.skip and tag in BREAK | {"br", "lb"}:
                self._break()
            return
        if self.capture is not None:              # inside a reference list: text only
            self.stack.append(None)
            return
        if self.skip or tag in SKIP or (tag == "sup" and "footnote-marker" in cls) \
                or (tag == "note" and a.get("type") in ("x-strongsMarkup", "variant")):
            self.skip += 1
            self.stack.append(None)
            return
        emitted = None
        if tag in BREAK:
            self._break()
        elif (tag == "i" and "footnote" in cls) or tag == "note":
            if not self._in("note"):
                self.cur.append("<note>")
                emitted = "</note>"
        elif tag in ("scripref", "reference") or (tag == "a" and ("refLink" in cls or a.get("data-ref"))):
            passage = a.get("passage", "")
            target = None
            for key in ("osisref", "parsed", "passage", "data-ref"):
                if a.get(key) and not (key == "passage" and re.search(r"[;,]", a[key])):
                    target = self.resolve(a[key])
                    if target:
                        break
            if self._in("ref") or self.capture is not None:
                pass
            elif target and not re.search(r"[;,]", passage):
                self.cur.append(f"<ref target={quoteattr(target)}>")
                emitted = "</ref>"
            elif tag == "scripref":
                # a list ('Pr 8:22-24; 16:4') or a bare scripRef: link each reference
                self.capture, self.capture_passage = [], passage
                emitted = "\x00capture"
        elif tag == "hi":
            t = a.get("type", "")
            if t in ("bold", "b") and not self._in("ref"):
                self.cur.append("<strong>"); emitted = "</strong>"
            elif t in ("italic", "i", "emphasis") and not self._in("ref"):
                self.cur.append("<em>"); emitted = "</em>"
        elif tag in EM and tag != "q" and not self._in("ref"):
            self.cur.append("<em>"); emitted = "</em>"
        elif tag in STRONG and not self._in("ref"):
            self.cur.append("<strong>"); emitted = "</strong>"
        self.stack.append(emitted)

    def handle_startendtag(self, tag, attrs):
        tag = tag.lower()
        if tag in BREAK | {"br", "lb"} and not self.skip:
            self._break()
        # every other self-closing tag (milestones, sync, scripCom) carries no text

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag in VOID:
            return
        if not self.stack:
            return
        emitted = self.stack.pop()
        if self.skip:
            self.skip -= 1
            return
        if emitted == "\x00capture":
            text = re.sub(r"\s+", " ", "".join(self.capture or [])).strip()
            self.capture = None
            self.cur.append(linkify_list(self.capture_passage or text))
            return
        if emitted:
            self.cur.append(emitted)
        if tag in BREAK:
            self._break()

    def handle_data(self, data):
        if self.skip:
            return
        if self.capture is not None:
            self.capture.append(data)
        else:
            self.cur.append(escape(clean_chars(data)))

    def result(self) -> list[str]:
        while self.stack:                         # close anything the source left open
            e = self.stack.pop()
            if e == "\x00capture":
                self.cur.append(linkify_list(re.sub(r"\s+", " ", "".join(self.capture or []))))
                self.capture = None
            elif e and not self.skip:
                self.cur.append(e)
            if self.skip:
                self.skip -= 1
        self._flush()
        return self.paras


def to_paragraphs(fragment: str, resolve=ref_target) -> list[str]:
    """Source markup -> list of <p> inner XML strings (already escaped, only our tags)."""
    if not fragment:
        return []
    p = _Inline(resolve)
    p.feed(fragment)
    p.close()
    return p.result()


def plain(fragment: str) -> str:
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment or ""))).strip()


# ── Model ──────────────────────────────────────────────────────────────────
@dataclass
class Comment:
    paras: list[str]
    heading: str | None = None
    lemma: str | None = None
    to_chapter: int | None = None
    to_verse: int | None = None
    source: str | None = None


@dataclass
class Chapter:
    intros: list[Comment] = field(default_factory=list)
    verses: dict[int, list[Comment]] = field(default_factory=dict)


@dataclass
class Book:
    code: str
    intros: list[Comment] = field(default_factory=list)
    chapters: dict[int, Chapter] = field(default_factory=dict)


@dataclass
class Commentary:
    id: str
    abbr: str
    title: str
    lang: str
    iso3: str
    direction: str = "ltr"
    author: str = ""
    year: str = ""
    license: str = ""
    region: str = ""
    source: str = ""
    books: dict[str, Book] = field(default_factory=dict)
    dropped: dict[str, int] = field(default_factory=dict)   # reason -> count, for the report

    def drop(self, reason: str, n: int = 1):
        self.dropped[reason] = self.dropped.get(reason, 0) + n

    def _chapter(self, code, ch) -> Chapter:
        b = self.books.setdefault(code, Book(code))
        return b.chapters.setdefault(int(ch), Chapter())

    def add(self, code: str, ch: int, v: int, c: Comment):
        if not c.paras:
            return
        if code not in ORDER:
            self.drop(f"unknown book {code}")
            return
        if ch < 1 or v < 1:
            self.drop("non-positive chapter/verse")
            return
        self._chapter(code, ch).verses.setdefault(int(v), []).append(c)

    def add_intro(self, code: str, ch: int | None, c: Comment):
        if not c.paras:
            return
        if code not in ORDER:
            self.drop(f"unknown book {code}")
            return
        if ch:
            self._chapter(code, ch).intros.append(c)
        else:
            self.books.setdefault(code, Book(code)).intros.append(c)

    # stats
    def counts(self) -> tuple[int, int, int, int]:
        verses = comments = intros = 0
        for b in self.books.values():
            intros += len(b.intros)
            for c in b.chapters.values():
                intros += len(c.intros)
                verses += len(c.verses)
                comments += sum(len(x) for x in c.verses.values())
        return len(self.books), verses, comments, intros


def _comment_xml(tag: str, c: Comment, n: int | None = None) -> str:
    attrs = ""
    if n:
        attrs += f' n="{n}"'
    if c.heading:
        attrs += f" heading={quoteattr(c.heading[:300])}"
    if c.lemma:
        attrs += f" lemma={quoteattr(c.lemma[:300])}"
    if c.to_chapter:
        attrs += f' toChapter="{c.to_chapter}"'
    if c.to_verse:
        attrs += f' toVerse="{c.to_verse}"'
    if c.source:
        attrs += f" source={quoteattr(c.source)}"
    return f"<{tag}{attrs}>" + "".join(f"<p>{p}</p>" for p in c.paras) + f"</{tag}>"


def normalise(doc: Commentary, v11n: str = "kjv"):
    """A chapter that has intros but no verse comments cannot be reached by the
    reader (it loads verses). Turn such intros into one comment on verse 1 that
    covers the whole chapter — that is exactly what chapter-level commentary is.
    A book with only intros gets the same treatment on 1:1."""
    for code, b in doc.books.items():
        for ch_no, ch in b.chapters.items():
            if ch.intros and not ch.verses:
                lens = chapter_lengths(code, v11n)
                last = lens[ch_no - 1] if 0 < ch_no <= len(lens) else None
                for c in ch.intros:
                    if last and last > 1 and not c.to_verse:
                        c.to_verse = last
                ch.verses[1] = ch.intros
                ch.intros = []
        if b.intros and not any(c.verses for c in b.chapters.values()):
            b.chapters.setdefault(1, Chapter()).verses.setdefault(1, []).extend(b.intros)
            b.intros = []


def to_xml(doc: Commentary, v11n: str = "kjv") -> bytes:
    normalise(doc, v11n)
    seq = 0
    total = 0
    out = []
    for code in sorted(doc.books, key=lambda c: ORDER[c]):
        b = doc.books[code]
        chapters = [(n, c) for n, c in sorted(b.chapters.items()) if c.verses]
        if not chapters:
            continue
        name = NAME[code]
        parts = [f'<book name={quoteattr(name)} code="{code}" canonicalOrder="{ORDER[code]}" '
                 f'testament="{TESTAMENT[code]}">']
        parts += [_comment_xml("intro", c) for c in b.intros]
        for ch_no, ch in chapters:
            parts.append(f'<chapter number="{ch_no}">')
            parts += [_comment_xml("intro", c) for c in ch.intros]
            for v_no in sorted(ch.verses):
                seq += 1
                total += 1
                parts.append(f'<verse number="{v_no}" bookName={quoteattr(name)} chapterNumber="{ch_no}" '
                             f'globalCanonicalSeq="{seq}">')
                parts += [_comment_xml("comment", c, i) for i, c in enumerate(ch.verses[v_no], start=1)]
                parts.append("</verse>")
            parts.append("</chapter>")
        parts.append("</book>")
        out.append("".join(parts))
    if not out:
        raise ValueError(f"{doc.id}: no comments to write")
    now = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    attrs = (f'xmlns="{NS}" id={quoteattr(doc.id)} type="commentary" translation={quoteattr(doc.title)} '
             f'abbreviation={quoteattr(doc.abbr)} bcp47Language={quoteattr(doc.lang)} '
             f'iso639_3={quoteattr(doc.iso3)} direction={quoteattr(doc.direction)}')
    if doc.year and str(doc.year).isdigit():
        attrs += f' year="{int(doc.year)}"'
    if doc.source:
        attrs += f" source={quoteattr(doc.source)}"
    attrs += f' ingestedAt="{now}"'
    if doc.license:
        attrs += f" license={quoteattr(doc.license)}"
    if doc.region:
        attrs += f" region={quoteattr(doc.region)}"
    attrs += f' totalVerses="{total}"'
    if doc.author:
        attrs += f" author={quoteattr(doc.author)}"
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<text ' + attrs + ">" + "".join(out) + "</text>\n").encode("utf-8")


def validate(xml: bytes) -> str | None:
    """xmllint against the schema. Returns None when valid, else the first errors."""
    with tempfile.NamedTemporaryFile(suffix=".xml", delete=False) as f:
        f.write(xml)
        path = f.name
    try:
        r = subprocess.run(["xmllint", "--noout", "--schema", str(SCHEMA), path],
                           capture_output=True, text=True)
        if r.returncode == 0:
            return None
        return "\n".join(r.stderr.splitlines()[:8])
    finally:
        Path(path).unlink(missing_ok=True)


# ── BaseX (writes go through curl, per CLAUDE.md) ───────────────────────────
class BaseX:
    def __init__(self, url="http://localhost:8984/rest", user="admin", password="admin"):
        self.url, self.auth = url.rstrip("/"), f"{user}:{password}"

    def _curl(self, *args, data: bytes | None = None) -> tuple[int, str]:
        cmd = ["curl", "-s", "-u", self.auth, "-w", "\n%{http_code}", *args]
        r = subprocess.run(cmd, input=data, capture_output=True)
        body, _, code = r.stdout.decode("utf-8", "replace").rpartition("\n")
        return int(code or 0), body

    def query(self, db: str, xq: str) -> str:
        code, body = self._curl("-G", f"{self.url}/{db}", "--data-urlencode", f"query={xq}")
        if code != 200:
            raise RuntimeError(f"BaseX query failed ({code}): {body[:300]}")
        return body

    def ensure_db(self, db: str):
        code, _ = self._curl(f"{self.url}/{db}")
        if code == 200:
            return
        code, body = self._curl("-X", "PUT", f"{self.url}/{db}")
        if code not in (200, 201):
            raise RuntimeError(f"cannot create database {db}: {code} {body[:200]}")

    def put(self, db: str, doc_id: str, xml: bytes):
        # .xml extension is load-bearing: every reader query is db:open(db, id || '.xml').
        # chop=false: BaseX otherwise trims the whitespace around inline elements, and
        # "That <em>religion</em> and" comes back as "That<em>religion</em>and".
        code, body = self._curl("-X", "PUT", "-H", "Content-Type: application/xml; charset=UTF-8",
                                "--data-binary", "@-", f"{self.url}/{db}/{doc_id}.xml?chop=false", data=xml)
        if code not in (200, 201):
            raise RuntimeError(f"PUT {doc_id} failed: {code} {body[:300]}")
        return body.strip()

    def copy_doc(self, src_db: str, dst_db: str, doc_id: str) -> bool:
        """Copy one edition into the staging database (so the staging reader has
        a Bible to read the commentary against). BaseX 9: db:replace."""
        exists = self.query(src_db, f"exists(db:open('{src_db}', '{doc_id}.xml'))").strip()
        if exists != "true":
            return False
        xq = f"db:replace('{dst_db}', '{doc_id}.xml', db:open('{src_db}', '{doc_id}.xml'))"
        body = ('<query xmlns="http://basex.org/rest"><text><![CDATA[' + xq + "]]></text></query>").encode()
        code, out = self._curl("-X", "POST", "-H", "Content-Type: application/xml",
                               "--data-binary", "@-", f"{self.url}/{dst_db}", data=body)
        if code != 200:
            raise RuntimeError(f"copy {doc_id} failed: {code} {out[:300]}")
        return True

    def abbreviations(self, db: str) -> dict[str, str]:
        xq = ("declare namespace rt='http://religioustext.org/schema/1.0'; "
              f"for $t in db:open('{db}')//rt:text return concat(lower-case($t/@abbreviation), '|', $t/@id)")
        out = {}
        for line in self.query(db, xq).splitlines():
            if "|" in line:
                a, i = line.split("|", 1)
                out[a] = i
        return out
