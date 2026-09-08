#!/usr/bin/env python3
"""
ingest_hadith.py — ingest hadith collections (Arabic matn + an English
translation) into the BaseX 'religioustext' database, in the SAME schema as the
Bible/Quran texts, so the existing reader machinery (verse-window scroll, book
nav, the Arabic+translation companion overlay) applies with minimal Phase-2
reader changes.

Source: fawazahmed0/hadith-api (https://github.com/fawazahmed0/hadith-api),
served over the jsdelivr CDN, free, no auth, no rate limit — the same family as
the quran-api used by ingest_quran.py. Editions are named "{lang}-{collection}",
e.g. ara-bukhari / eng-bukhari, each as one JSON blob:
    { "metadata": { "name", "sections": {num: name}, "section_details": {...} },
      "hadiths":  [ { "hadithnumber", "arabicnumber", "text",
                      "grades": [{name, grade}...], "reference": {book, hadith} } ] }

Schema mapping (mirrors the Quran's surah->book / ayah->verse mapping):
    collection      -> <text type="hadith">  (one document per collection+language)
    section (book)  -> <book name=sectionName code=sectionNum canonicalOrder=sectionNum>
    one             -> <chapter number="1">  per section
    hadith          -> <verse number=hadithnumber bookName=sectionName chapterNumber="1"
                              globalCanonicalSeq=... grade=... reference="book:hadith">text</verse>

Arabic = base edition (baseText=""), English = translation (baseText=<arabic id>),
so the reader's translation-companion picker pairs them (English beneath the
Arabic) exactly as for the Quran. Editions align by globalCanonicalSeq, which is
derived from the (edition-shared) hadithnumber so the same hadith gets the same
seq in both — gaps tolerated, no running-index desync.

Repeatable: re-running replaces the documents. No third-party deps (stdlib + curl).

Usage:
    python3 ingest_hadith.py                  # all collections below
    python3 ingest_hadith.py --collection nawawi   # one collection
    python3 ingest_hadith.py --list           # list configured collections
"""
import json
import subprocess
import tempfile
import os
import sys
import html

BASEX = "http://localhost:8984/rest/religioustext"
AUTH  = "admin:admin"
NS    = "http://religioustext.org/schema/1.0"
CDN   = "https://cdn.jsdelivr.net/gh/fawazahmed0/hadith-api@1/editions"

# Collections available in fawazahmed0/hadith-api that match the transcript scan.
#   key      = the api edition stem (ara-<key> / eng-<key>)
#   code     = short token -> link/source abbreviation ("<code>-AR" / "<code>-EN")
#   display  = human collection name
# NOT in this dataset (need another source later): Musnad Ahmad, Mishkat,
# al-Tabarani, al-Bayhaqi, al-Darimi, Riyad as-Salihin, Bulugh al-Maram,
# Al-Adab al-Mufrad.
COLLECTIONS = [
    ("bukhari",  "BUK", "Sahih al-Bukhari"),
    ("muslim",   "MUS", "Sahih Muslim"),
    ("abudawud", "ABD", "Sunan Abi Dawud"),
    ("tirmidhi", "TIR", "Jami` at-Tirmidhi"),
    ("nasai",    "NAS", "Sunan an-Nasa'i"),
    ("ibnmajah", "IBM", "Sunan Ibn Majah"),
    ("malik",    "MAL", "Muwatta Malik"),
    ("nawawi",   "NAW", "Forty Hadith of an-Nawawi"),
    ("qudsi",    "QUD", "Forty Hadith Qudsi"),
    ("dehlawi",  "DEH", "Forty Hadith of Shah Waliullah Dehlawi"),
]


def esc_attr(s):
    return html.escape(str(s), quote=True)


def esc_text(s):
    return html.escape(str(s), quote=False)


def fetch_edition(name):
    """Fetch one edition JSON ({lang}-{collection}). Tries .min.json then .json
    (the api docs recommend a fallback between the two)."""
    last = None
    for suffix in (".min.json", ".json"):
        url = f"{CDN}/{name}{suffix}"
        r = subprocess.run(["curl", "-sL", "--max-time", "120", url],
                           capture_output=True, text=True, timeout=150)
        if r.returncode == 0 and r.stdout.strip():
            try:
                doc = json.loads(r.stdout)
                if "hadiths" in doc:
                    return doc
            except json.JSONDecodeError as e:
                last = f"JSON parse error: {e}"
        else:
            last = f"curl rc={r.returncode} {r.stderr[:160]}"
    raise SystemExit(f"Fetch failed for {name}: {last}")


def seq_strategy(hadiths):
    """Pick a globalCanonicalSeq derivation from hadithnumber that is identical
    across editions and collision-free. Integer hadithnumbers (the common case)
    map 1:1; if any are fractional, scale x1000 so 1.1/1.2 stay distinct and
    ordered. Returns a function hadithnumber -> int seq."""
    all_int = True
    for h in hadiths:
        try:
            v = float(h["hadithnumber"])
        except (TypeError, ValueError):
            v = 0.0
        if v != int(v):
            all_int = False
            break
    if all_int:
        return lambda hn: int(float(hn))
    print("    note: non-integer hadith numbers present -> seq scaled x1000")
    return lambda hn: int(round(float(hn) * 1000))


def grade_str(grades):
    if not grades:
        return ""
    parts = []
    for g in grades:
        name = (g.get("name") or "").strip()
        grade = (g.get("grade") or "").strip()
        if name and grade:
            parts.append(f"{name}: {grade}")
        elif grade:
            parts.append(grade)
    return "; ".join(parts)


def build_xml(edition, doc_id, translation, abbr, direction, license_, source, lang, base_text):
    meta = edition.get("metadata", {})
    sections = meta.get("sections", {}) or {}
    hadiths = edition["hadiths"]
    seq_of = seq_strategy(hadiths)

    # Group hadith by their section (reference.book), preserving section order.
    by_section = {}
    for h in hadiths:
        ref = h.get("reference") or {}
        book = ref.get("book")
        if book is None:
            book = 1
        by_section.setdefault(int(book), []).append(h)

    base_attr = f' baseText="{esc_attr(base_text)}"' if base_text else ''
    out = ['<?xml version="1.0" encoding="UTF-8"?>']
    out.append(
        f'<text xmlns="{NS}" id="{esc_attr(doc_id)}" type="hadith" lang="{esc_attr(lang)}"'
        f' translation="{esc_attr(translation)}" abbreviation="{esc_attr(abbr)}"'
        f' direction="{direction}" license="{esc_attr(license_)}"'
        f' source="{esc_attr(source)}"{base_attr}'
        f' totalVerses="{len(hadiths)}">'
    )
    for snum in sorted(by_section):
        sname = sections.get(str(snum)) or f"Book {snum}"
        out.append(
            f'  <book name="{esc_attr(sname)}" code="{snum}" canonicalOrder="{snum}">'
        )
        out.append(f'    <chapter number="1" title="{esc_attr(sname)}">')
        for h in sorted(by_section[snum], key=lambda x: float(x["hadithnumber"])):
            hn = h["hadithnumber"]
            seq = seq_of(hn)
            ref = h.get("reference") or {}
            refstr = f'{ref.get("book", "")}:{ref.get("hadith", "")}'
            grade = grade_str(h.get("grades"))
            grade_attr = f' grade="{esc_attr(grade)}"' if grade else ''
            out.append(
                f'      <verse number="{esc_attr(hn)}" bookName="{esc_attr(sname)}" chapterNumber="1"'
                f' globalCanonicalSeq="{seq}" reference="{esc_attr(refstr)}"{grade_attr}>'
                f'{esc_text(h.get("text", ""))}</verse>'
            )
        out.append('    </chapter>')
        out.append('  </book>')
    out.append('</text>')
    return "\n".join(out), len(by_section)


def put_doc(doc_id, xml):
    with tempfile.NamedTemporaryFile("w", suffix=".xml", encoding="utf-8", delete=False) as f:
        f.write(xml)
        tmp = f.name
    try:
        r = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-u", AUTH,
             "-X", "PUT", "-H", "Content-Type: application/xml",
             "--data-binary", f"@{tmp}", f"{BASEX}/{doc_id}.xml"],
            capture_output=True, text=True, timeout=300)
        return r.stdout.strip()
    finally:
        os.unlink(tmp)


def ingest_collection(key, code, display):
    print(f"\n{display}  ({key})")
    ar_id = f"hadith-{key}-ar"
    en_id = f"hadith-{key}-en"
    ar_src = f"{CDN}/ara-{key}.json"
    en_src = f"{CDN}/eng-{key}.json"

    print(f"  fetching ara-{key} ...")
    ar = fetch_edition(f"ara-{key}")
    print(f"  fetching eng-{key} ...")
    en = fetch_edition(f"eng-{key}")
    print(f"  hadith: ar={len(ar['hadiths'])} en={len(en['hadiths'])}")

    docs = [
        # Arabic = base edition (no baseText); the matn is classical/public domain.
        (ar_id, ar, f"{display} (Arabic)", f"{code}-AR", "rtl",
         "Arabic matn — classical, public domain", ar_src, "ar", ""),
        # English = translation, paired to the Arabic base (companion overlay).
        (en_id, en, f"{display} (English)", f"{code}-EN", "ltr",
         "Translation via fawazahmed0/hadith-api — verify provenance per edition",
         en_src, "en", ar_id),
    ]
    for doc_id, edition, trans, abbr, direction, lic, src, lang, base_text in docs:
        xml, nbooks = build_xml(edition, doc_id, trans, abbr, direction, lic, src, lang, base_text)
        httpcode = put_doc(doc_id, xml)
        print(f"  PUT {doc_id}: HTTP {httpcode}  ({nbooks} books, {len(edition['hadiths'])} hadith)")


def main():
    args = sys.argv[1:]
    if "--list" in args:
        for key, code, display in COLLECTIONS:
            print(f"  {key:10s} {code:5s} {display}")
        return
    chosen = COLLECTIONS
    if "--collection" in args:
        i = args.index("--collection")
        want = args[i + 1]
        chosen = [c for c in COLLECTIONS if c[0] == want]
        if not chosen:
            raise SystemExit(f"Unknown collection '{want}'. Use --list to see options.")
    for key, code, display in chosen:
        ingest_collection(key, code, display)
    print("\nDone.")


if __name__ == "__main__":
    main()
