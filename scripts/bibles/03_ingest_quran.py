#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
ingest_quran.py — ingest an Arabic Quran (Uthmani) + one English translation
(Pickthall, public domain) into the BaseX 'religioustext' database, in the SAME
schema as the Bible texts, stamping BOTH globalCanonicalSeq (mushaf order) and
globalChronologicalSeq (revelation order) from the start — because for the Quran
the canonical (presentation) order is NOT the chronological (revelation) order.

Schema mapping:
    surah  -> <book> (114), @canonicalOrder = surah number
    one    -> <chapter number="1" title=...>  per surah
              (Arabic edition: authentic Arabic surah name; a translation: English
               name + meaning. book/@name stays the English transliteration in
               BOTH editions as the cross-edition verse-alignment key.)
    ayah   -> <verse number=numberInSurah bookName=... chapterNumber="1"
                     globalCanonicalSeq=... globalChronologicalSeq=...>text</verse>
    type="quran"

Source: alquran.cloud (free, no auth). Arabic edition = quran-uthmani; translations
= Pickthall + Yusuf Ali (English) + Sablukov (Russian), all public domain; see
TRANSLATIONS in main() for the per-edition licensing notes. Repeatable: re-running
replaces the documents. No third-party Python deps (stdlib + curl).

Usage:  python3 ingest_quran.py
"""
import json
import subprocess
import tempfile
import os
import html

BASEX = "http://localhost:8984/rest/religioustext"
AUTH  = "admin:admin"
NS    = "http://religioustext.org/schema/1.0"

# Standard Egyptian/Cairo revelation order: surah numbers in the order revealed
# (index 0 = first revealed). FLAG: this is the traditional/Azhar ordering used
# by most printed Qurans; scholars debate fine points. Verify before relying on
# it for anything beyond a chronological reading view.
REVELATION_ORDER = [
     96, 68, 73, 74,  1, 111, 81, 87, 92, 89,
     93, 94, 103, 100, 108, 102, 107, 109, 105, 113,
    114, 112, 53, 80, 97, 91, 85, 95, 106, 101,
     75, 104, 77, 50, 90, 86, 54, 38,  7, 72,
     36, 25, 35, 19, 20, 56, 26, 27, 28, 17,
     10, 11, 12, 15,  6, 37, 31, 34, 39, 40,
     41, 42, 43, 44, 45, 46, 51, 88, 18, 16,
     71, 14, 21, 23, 32, 52, 67, 69, 70, 78,
     79, 82, 84, 30, 29, 83,  2,  8,  3, 33,
     60,  4, 99, 57, 47, 13, 55, 76, 65, 98,
     59, 24, 22, 63, 58, 49, 66, 64, 61, 62,
     48,  5,  9, 110,
]

# Canonical surah-name transliterations (quran.com / Tanzil style), keyed by surah
# number. Used for book/@name (and the verse @bookName alignment key) in every
# edition, so all editions stay aligned and the reader shows a consistent, standard
# romanization (e.g. "Al-Baqarah", not the source feed's "Al-Baqara"). The proper
# Arabic name still comes from the source and lives in book/@arabicName.
SURAH_NAMES = {
    1: "Al-Fatihah", 2: "Al-Baqarah", 3: "Ali 'Imran", 4: "An-Nisa", 5: "Al-Ma'idah",
    6: "Al-An'am", 7: "Al-A'raf", 8: "Al-Anfal", 9: "At-Tawbah", 10: "Yunus",
    11: "Hud", 12: "Yusuf", 13: "Ar-Ra'd", 14: "Ibrahim", 15: "Al-Hijr",
    16: "An-Nahl", 17: "Al-Isra", 18: "Al-Kahf", 19: "Maryam", 20: "Taha",
    21: "Al-Anbya", 22: "Al-Hajj", 23: "Al-Mu'minun", 24: "An-Nur", 25: "Al-Furqan",
    26: "Ash-Shu'ara", 27: "An-Naml", 28: "Al-Qasas", 29: "Al-'Ankabut", 30: "Ar-Rum",
    31: "Luqman", 32: "As-Sajdah", 33: "Al-Ahzab", 34: "Saba", 35: "Fatir",
    36: "Ya-Sin", 37: "As-Saffat", 38: "Sad", 39: "Az-Zumar", 40: "Ghafir",
    41: "Fussilat", 42: "Ash-Shura", 43: "Az-Zukhruf", 44: "Ad-Dukhan", 45: "Al-Jathiyah",
    46: "Al-Ahqaf", 47: "Muhammad", 48: "Al-Fath", 49: "Al-Hujurat", 50: "Qaf",
    51: "Adh-Dhariyat", 52: "At-Tur", 53: "An-Najm", 54: "Al-Qamar", 55: "Ar-Rahman",
    56: "Al-Waqi'ah", 57: "Al-Hadid", 58: "Al-Mujadila", 59: "Al-Hashr", 60: "Al-Mumtahanah",
    61: "As-Saff", 62: "Al-Jumu'ah", 63: "Al-Munafiqun", 64: "At-Taghabun", 65: "At-Talaq",
    66: "At-Tahrim", 67: "Al-Mulk", 68: "Al-Qalam", 69: "Al-Haqqah", 70: "Al-Ma'arij",
    71: "Nuh", 72: "Al-Jinn", 73: "Al-Muzzammil", 74: "Al-Muddaththir", 75: "Al-Qiyamah",
    76: "Al-Insan", 77: "Al-Mursalat", 78: "An-Naba", 79: "An-Nazi'at", 80: "'Abasa",
    81: "At-Takwir", 82: "Al-Infitar", 83: "Al-Mutaffifin", 84: "Al-Inshiqaq", 85: "Al-Buruj",
    86: "At-Tariq", 87: "Al-A'la", 88: "Al-Ghashiyah", 89: "Al-Fajr", 90: "Al-Balad",
    91: "Ash-Shams", 92: "Al-Layl", 93: "Ad-Duha", 94: "Ash-Sharh", 95: "At-Tin",
    96: "Al-'Alaq", 97: "Al-Qadr", 98: "Al-Bayyinah", 99: "Az-Zalzalah", 100: "Al-'Adiyat",
    101: "Al-Qari'ah", 102: "At-Takathur", 103: "Al-'Asr", 104: "Al-Humazah", 105: "Al-Fil",
    106: "Quraysh", 107: "Al-Ma'un", 108: "Al-Kawthar", 109: "Al-Kafirun", 110: "An-Nasr",
    111: "Al-Masad", 112: "Al-Ikhlas", 113: "Al-Falaq", 114: "An-Nas",
}


def fetch_edition(edition):
    url = f"https://api.alquran.cloud/v1/quran/{edition}"
    r = subprocess.run(["curl", "-s", "--max-time", "90", url],
                       capture_output=True, text=True, timeout=120)
    if r.returncode != 0 or not r.stdout.strip():
        raise SystemExit(f"Fetch failed for {edition}: rc={r.returncode} {r.stderr[:200]}")
    doc = json.loads(r.stdout)
    if doc.get("code") != 200:
        raise SystemExit(f"API error for {edition}: {doc.get('status')}")
    return doc["data"]["surahs"]


def esc_attr(s):
    return html.escape(str(s), quote=True)


def esc_text(s):
    return html.escape(str(s), quote=False)


def build_chrono_map(surahs):
    """(surahNum, ayahNumberInSurah) -> chronological seq, by revelation order."""
    by_num = {s["number"]: s for s in surahs}
    seq = 0
    cmap = {}
    for surah_num in REVELATION_ORDER:
        for a in by_num[surah_num]["ayahs"]:
            seq += 1
            cmap[(surah_num, a["numberInSurah"])] = seq
    return cmap


def build_xml(surahs, doc_id, translation, abbr, direction, license_, source, lang, base_text, chrono_map):
    out = ['<?xml version="1.0" encoding="UTF-8"?>']
    # baseText couples a translation to the specific Arabic edition it renders.
    # The Arabic base text itself carries no baseText (it IS the base); each
    # translation points at its base id, so different Arabic editions (e.g.
    # different qira'at / readings) can carry their own translations without
    # ever being mismatched against the wrong Arabic text. lang aids grouping.
    base_attr = f' baseText="{esc_attr(base_text)}"' if base_text else ''
    out.append(
        f'<text xmlns="{NS}" id="{esc_attr(doc_id)}" type="quran" lang="{esc_attr(lang)}"'
        f' translation="{esc_attr(translation)}" abbreviation="{esc_attr(abbr)}"'
        f' direction="{direction}" license="{esc_attr(license_)}"'
        f' source="{esc_attr(source)}"{base_attr}'
        f' totalVerses="{sum(len(s["ayahs"]) for s in surahs)}">'
    )
    canon = 0
    for s in surahs:                      # mushaf order (1..114)
        snum = s["number"]
        ename = SURAH_NAMES.get(snum, s["englishName"])
        out.append(
            f'  <book name="{esc_attr(ename)}" code="{snum}" canonicalOrder="{snum}"'
            f' arabicName="{esc_attr(s["name"])}"'
            f' revelationType="{esc_attr(s.get("revelationType", ""))}">'
        )
        # Chapter title is language-specific. The Arabic edition carries the
        # authentic Arabic surah name (the original Quran has no English text);
        # a translation carries the English name + meaning. The shared book @name
        # stays the English transliteration (cross-edition alignment key) and the
        # Arabic name also lives in book/@arabicName.
        if lang == "ar":
            title = s["name"]
        else:
            title = f'{ename} - {s.get("englishNameTranslation", "")}'.strip(" -")
        out.append(f'    <chapter number="1" title="{esc_attr(title)}">')
        for a in s["ayahs"]:
            canon += 1
            ay = a["numberInSurah"]
            chrono = chrono_map[(snum, ay)]
            out.append(
                f'      <verse number="{ay}" bookName="{esc_attr(ename)}" chapterNumber="1"'
                f' globalCanonicalSeq="{canon}" globalChronologicalSeq="{chrono}">'
                f'{esc_text(a["text"])}</verse>'
            )
        out.append('    </chapter>')
        out.append('  </book>')
    out.append('</text>')
    return "\n".join(out)


def put_doc(doc_id, xml):
    with tempfile.NamedTemporaryFile("w", suffix=".xml", encoding="utf-8", delete=False) as f:
        f.write(xml)
        tmp = f.name
    try:
        r = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-u", AUTH,
             "-X", "PUT", "-H", "Content-Type: application/xml",
             "--data-binary", f"@{tmp}", f"{BASEX}/{doc_id}.xml"],
            capture_output=True, text=True, timeout=180)
        return r.stdout.strip()
    finally:
        os.unlink(tmp)


def main():
    assert len(REVELATION_ORDER) == 114 and sorted(REVELATION_ORDER) == list(range(1, 115)), \
        "REVELATION_ORDER must be a permutation of 1..114"

    AR_ID = "quran-ar-uthmani"
    # Translations to pair with the Arabic base. PUBLIC DOMAIN ONLY.
    # Surveyed alquran.cloud across the UI languages (en/es/fi/sv/ru/zh): the only
    # unambiguously public-domain options are the two English editions below plus
    # Russian Sablukov (1878, author d.1880). Spanish (Cortes/Asad/Bornez/Garcia),
    # Swedish (Bernstrom) and Chinese (Ma Jian/Ma Zhonggang) are all modern & in
    # copyright; Finnish has NO edition on this source. Russian Krachkovsky (d.1951)
    # is likely EU-PD (life+70 from 2022) but posthumously published (1963) with
    # possible Russian wartime term extensions -> deferred pending a decision.
    #   (edition, doc_id, translation, abbr, lang, license)
    TRANSLATIONS = [
        ("en.pickthall", "quran-en-pickthall", "Quran - Pickthall (English)", "Q-EN", "en",
         "Public domain (M. Pickthall, 1930)"),
        ("en.yusufali", "quran-en-yusufali", "Quran - Yusuf Ali (English)", "Q-YA", "en",
         "Public domain in EU/life+70 & Pakistan (A. Yusuf Ali, 1934); US URAA copyright to 2033"),
        ("ru.sablukov", "quran-ru-sablukov", "Quran - Sablukov (Russian)", "Q-SAB", "ru",
         "Public domain (G. Sablukov, 1878; author d.1880)"),
    ]

    print("Fetching Arabic (quran-uthmani) ...")
    ar = fetch_edition("quran-uthmani")
    chrono = build_chrono_map(ar)         # same ayah numbering across all editions

    docs = [(AR_ID, ar, "Quran - Uthmani (Arabic)", "Q-AR", "rtl",
             "Public domain (Tanzil Uthmani text)", "alquran.cloud / Tanzil", "ar", "")]
    for edition, doc_id, trans, abbr, lang, lic in TRANSLATIONS:
        print(f"Fetching {lang} ({edition}) ...")
        surahs = fetch_edition(edition)
        docs.append((doc_id, surahs, trans, abbr, "ltr", lic, "alquran.cloud", lang, AR_ID))

    for doc_id, surahs, trans, abbr, direction, lic, src, lang, base_text in docs:
        xml = build_xml(surahs, doc_id, trans, abbr, direction, lic, src, lang, base_text, chrono)
        code = put_doc(doc_id, xml)
        nayah = sum(len(s["ayahs"]) for s in surahs)
        print(f"  PUT {doc_id}: HTTP {code}  ({len(surahs)} surahs, {nayah} ayat)")

    print("Done.")


if __name__ == "__main__":
    main()
