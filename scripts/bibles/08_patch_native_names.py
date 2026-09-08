#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (C) 2026 Christa Claw
"""
patch_native_names.py — set each edition's stamped @translation to the
edition's OWN language ("the name should be in the same language as the body
of text it represents" — Christa, 2026-07-03).

Principle:
  * Proper-noun editions keep their names (King James Version, Textus
    Receptus, Biblia Sacra Vulgata — the last two already ARE body-language).
  * Translated-scripture editions lead with their native self-designation;
    IF AND ONLY IF the currently stored name is English, it is kept in
    parentheses after the native name (Christa's rule, 2026-07-03 refinement).
    Editions never stored in English gain no artificial parenthetical.

Usage:
    python3 patch_native_names.py --list    # audit current @translation values
    python3 patch_native_names.py           # apply NAME_MAP (idempotent)

⚠️ Run --list FIRST and confirm the parenthetical parts below match the
actually-stored English names; adjust before applying. Non-Latin names are
Claude-drafted, PENDING NATIVE REVIEW (same queue as the hi/he UI bundles).
Stdlib only; reuses stamp_chronological's REST helpers.
"""

import sys
import importlib
sc = importlib.import_module("05_stamp_chronological")

# doc-id (without .xml) -> new @translation value: native name first, the
# previously stored ENGLISH name in parentheses (inner parens flattened).
# Editions NOT listed are left untouched (KJV, ASV, TR, Vulgate, the Spanish
# and other Latin-script natives, Baibal Olcim, ...).
NAME_MAP = {
    # ── Bibles (verified against --list 2026-07-04) ────────────────────
    "bible-he-delitzsch":  "תנ״ך והברית החדשה (Hebrew Bible: Masoretic OT + Delitzsch NT)",
    "bible-he-wlc":        "כתב־יד לנינגרד (Westminster Leningrad Codex)",
    "bible-irvhin-2019":   "इंडियन रिवाइज़्ड वर्ज़न (Indian Revised Version, IRV)",
    "bible-ar-vandyck":    "الكتاب المقدس — ترجمة فان دايك (Smith & Van Dyck)",
    # zh-cuv already native-first (和合本 (Chinese Union Version)) — untouched.
    # ru-synodal already native with NO stored English — untouched per the rule.

    # ── Qur'an & hadith, Arabic-body docs (same principle: native name first,
    #    stored English transliteration kept in parens) ──────────────────
    "quran-ar-uthmani":    "القرآن الكريم — الرسم العثماني (Quran - Uthmani)",
    "quran-ru-sablukov":   "Коран — перевод Саблукова (Quran - Sablukov)",
    "hadith-bukhari-ar":   "صحيح البخاري (Sahih al-Bukhari)",
    "hadith-muslim-ar":    "صحيح مسلم (Sahih Muslim)",
    "hadith-abudawud-ar":  "سنن أبي داود (Sunan Abi Dawud)",
    "hadith-tirmidhi-ar":  "جامع الترمذي (Jami` at-Tirmidhi)",
    "hadith-nasai-ar":     "سنن النسائي (Sunan an-Nasa'i)",
    "hadith-ibnmajah-ar":  "سنن ابن ماجه (Sunan Ibn Majah)",
    "hadith-malik-ar":     "موطأ مالك (Muwatta Malik)",
    "hadith-nawawi-ar":    "الأربعون النووية (Forty Hadith of an-Nawawi)",
    "hadith-qudsi-ar":     "الأحاديث القدسية الأربعون (Forty Hadith Qudsi)",
    "hadith-dehlawi-ar":   "الأربعون لشاه ولي الله الدهلوي (Forty Hadith of Shah Waliullah Dehlawi)",
    # English-body docs (quran-en-*, hadith-*-en, LDS) stay English — correct.
}


def get_current_names():
    result = sc.xquery(
        f"declare namespace rt='{sc.NS}';"
        f" for $t in db:open('religioustext')//rt:text"
        f" order by string($t/@id)"
        f" return concat(string($t/@id), ' | ', string($t/@bcp47Language),"
        f" ' | ', string($t/@translation))"
    )
    return [l for l in result.splitlines() if l.strip()]


def patch(doc_id, new_name):
    name_esc = sc._escape_xq(new_name)
    sc.xquery_update(
        f"declare namespace rt='{sc.NS}';"
        f" let $t := db:open('religioustext','{doc_id}.xml')/rt:text"
        f" return replace value of node $t/@translation with '{name_esc}'"
    )


def main():
    if "--list" in sys.argv:
        print("Current @translation values (id | lang | translation):\n")
        for line in get_current_names():
            print("  " + line)
        return

    for doc_id, new_name in NAME_MAP.items():
        try:
            patch(doc_id, new_name)
            print(f"  {doc_id}: -> {new_name}")
        except Exception as e:
            print(f"  ERROR {doc_id}: {e}")
    print("\nDone. Restart/reload the app to refresh the source catalog.")


if __name__ == "__main__":
    main()
