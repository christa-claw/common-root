# -*- coding: utf-8 -*-
"""Audit our Hafs (alquran.cloud / Tanzil) against the King Fahd Complex's own Hafs.

Reads two local files and publishes nothing. It answers the question the FB1776
page had to confess it could not: what IS the text we are showing?

Their side : the .doc inside the KFGQPC browser zip, read as UTF-16LE.
             Structure is paragraph-based: a heading paragraph "suuratu X",
             then a basmala paragraph, then verse paragraphs with the ayah
             number placed AFTER each verse. 114 headings, exactly.
Our side   : a BaseX export, surah|name|ayah|text, surah by document position.

Three tiers of agreement are reported, because "different" is not one thing:
  exact      - codepoint for codepoint
  diacritics - same letters, different vowel/pause marks
  hamza      - same consonantal rasm, different hamza seat or madda spelling
               (KFGQPC writes  أٓ  where Tanzil writes  ءَا )
  RASM       - a real difference in the consonantal skeleton. This is the
               number that matters.
"""
import zipfile, os, re, sys, unicodedata

CTRL = re.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F]")
KEEP = re.compile("[^\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF \n\r]")
NUM = re.compile("[٠-٩]+")
HEADP = re.compile("^سُورَة")
AR2ASCII = {0x0660 + i: 0x30 + i for i in range(10)}

# alquran.cloud prepends the basmala to ayah 1 of every surah except 1 (where
# it IS ayah 1) and 9 (where it is absent); KFGQPC prints it unnumbered. Left
# in place it would manufacture ~112 differences that are not differences.
BASMALA_RASM = "بسم الله الرحمن الرحيم"

SRC = os.environ.get("QIRAAT_DIR", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "sources", "qiraat"))
ZIP = os.path.join(SRC, "UthmanicHafs1-Ex1-Ver12-browser.zip")
OURS = os.path.join(SRC, "ours-hafs.txt")


def norm(s):
    s = unicodedata.normalize("NFC", s)
    s = s.replace("﻿", "").replace("ـ", "")            # BOM, tatweel
    return re.sub(r"\s+", " ", s).strip()


DROP = ("Mn",   # vowels, tanwin, sukun, superscript alif, pause/waqf marks
        "Me",   # enclosing marks
        "Cf",   # BOM and other format controls
        "Lm",   # the small silent letters U+06E5 waw / U+06E6 yeh
        "So",   # U+06DE hizb marker, U+06E9 sajdah sign
        "Po")   # Arabic punctuation


# Tanzil writes an alif-maqsura as the CARRIER of a superscript (dagger) alif
# -- yaa-maqsura + U+0670 -- where KFGQPC prints the dagger alif alone. Neither
# letter is in the rasm, so both the mark and its carrier go.
DAGGER = re.compile("\u0649?\u0670")


def bare(s):
    """Leave letters only. Everything a scribe adds ON TOP of the consonantal
    line -- vowels, waqf signs, hizb and sajdah markers, the small silent
    letters -- is notation about the text, not the text."""
    s = DAGGER.sub("", unicodedata.normalize("NFD", norm(s)))
    s = "".join(c for c in s if unicodedata.category(c) not in DROP)
    return re.sub(r"\s+", " ", s).strip()


def skeleton(s):
    s = bare(s)
    for a in "آأإٱ":
        s = s.replace(a, "ا")
    return s.replace("ة", "ه").replace("ى", "ي")


def rasm(s):
    """The consonantal skeleton proper: hamza is not a rasm letter, and its
    seat is an orthographic choice, so both are folded away."""
    s = skeleton(s).replace("ء", "").replace("ؤ", "و").replace("ئ", "ي")
    return re.sub(r"\s+", " ", s).strip()


def strip_basmala(text, surah):
    if surah in (1, 9):
        return text, False
    words = norm(text).split(" ")
    for n in (4, 5):
        if len(words) > n and rasm(" ".join(words[:n])) == rasm(BASMALA_RASM):
            return " ".join(words[n:]), True
    return text, False


# ---- theirs ---------------------------------------------------------------
z = zipfile.ZipFile(ZIP)
doc = [i.filename for i in z.infolist() if i.filename.lower().endswith(".doc")][0]
body = KEEP.sub(" ", CTRL.sub("", z.read(doc).decode("utf-16-le", errors="ignore")))
paras = [p.strip() for p in body.split("\r") if p.strip()]
heads = [i for i, p in enumerate(paras) if HEADP.match(p) and not NUM.search(p)]
if len(heads) != 114:
    sys.exit("expected 114 surah headings, found %d" % len(heads))
bounds = heads + [len(paras)]

theirs, skipped = {}, 0
for k in range(114):
    si = k + 1
    sec = " ".join(paras[bounds[k] + 1: bounds[k + 1]])
    sec, _ = strip_basmala(sec, si)
    expect, pos = 1, 0
    for m in NUM.finditer(sec):
        n = int(m.group().translate(AR2ASCII))
        if n != expect:                    # stray 0 in Maryam, appendix after An-Nas
            skipped += 1
            continue
        theirs[(si, n)] = norm(sec[pos:m.start()])
        pos, expect = m.end(), expect + 1

# ---- ours -----------------------------------------------------------------
ours, names, stripped = {}, {}, 0
for line in open(OURS, encoding="utf-8"):
    line = line.lstrip("﻿").rstrip("\n")
    if not line.strip():
        continue
    f = line.split("|", 3)
    if len(f) != 4:
        continue
    s, names[int(f[0])], a, t = int(f[0]), f[1], int(f[2]), f[3]
    t, hit = strip_basmala(norm(t), s)
    stripped += hit
    ours[(s, a)] = t

# ---- report ---------------------------------------------------------------
print("ours  : %d verses, %d surahs (%s .. %s); basmala stripped from %d ayah-1s"
      % (len(ours), len({s for s, _ in ours}), names.get(1), names.get(114), stripped))
print("theirs: %d verses, 114 surah headings; %d stray numerals skipped"
      % (len(theirs), skipped))

both = sorted(set(theirs) & set(ours))
print("shared: %d | only-theirs: %d | only-ours: %d"
      % (len(both), len(set(theirs) - set(ours)), len(set(ours) - set(theirs))))
for k in sorted(set(theirs) ^ set(ours))[:10]:
    print("   unmatched key: %s" % (k,))
if not both:
    sys.exit("no overlap")

def tight(s):
    return rasm(s).replace(" ", "")


tiers = {"exact": 0, "diacritics": 0, "hamza": 0, "words": 0, "rasm": 0}
examples = []
for k in both:
    t, o = theirs[k], ours[k]
    if t == o:
        tiers["exact"] += 1
    elif skeleton(t) == skeleton(o):
        tiers["diacritics"] += 1
    elif rasm(t) == rasm(o):
        tiers["hamza"] += 1
    elif tight(t) == tight(o):
        tiers["words"] += 1
    else:
        tiers["rasm"] += 1
        examples.append(k)

n = len(both)
for label in ("exact", "diacritics", "hamza", "words", "rasm"):
    print("%-28s: %5d  (%6.2f%%)" % (
        {"exact": "identical",
         "diacritics": "diacritics differ only",
         "hamza": "hamza spelling differs only",
         "words": "word division differs only",
         "rasm": "CONSONANTAL RASM DIFFERS"}[label],
        tiers[label], 100.0 * tiers[label] / n))

for k in examples:
    print("\n  %d:%d\n    theirs: %s\n    ours  : %s\n    r-them: %s\n    r-ours: %s"
          % (k[0], k[1], theirs[k][:90], ours[k][:90],
             rasm(theirs[k])[:90], rasm(ours[k])[:90]))
