#!/usr/bin/env python3
"""
convert_diaglott.py — turn the custom Emphatic Diaglott interlinear JSON
(greek / interlinear / translation / footnotes per verse) into the FLAT shape
that import_json_bible.py --format flat consumes:

    {"verses": [{"book": <40-66>, "chapter": <int>, "verse": <int>, "text": "..."}, ...]}

The Diaglott is NT-only, so emitted book numbers are 40-66 — import with --allow-partial.
Re-run it whenever you finish another book; it converts whatever books are present.

INPUT shape (one book, or many)::

    {
      "name": "THE EMPHATIC DIAGLOTT",
      "books": { "book": "Matthew", "chapters": [
          {"chapter": 1, "verses": {
              "1": {"greek": "...", "interlinear": "...", "translation": "...", "footnotes": ""},
              "2": { ... }, ... }},
          { "chapter": 2, ... }, ... ]}
    }

  `books` may be a single {book, chapters} object (one file per book) OR a list of
  them (whole NT in one file). Both are handled. `verses` may be an object keyed by
  verse-number string, or a plain array.

CHOICE OF TEXT FIELD (--field) — the Diaglott carries TWO English renderings:
  * translation  Wilson's polished "Emphatic Version" (natural reading text).
                 John 1:1 reads "...and the LOGOS was God".
  * interlinear  the literal word-for-word gloss. John 1:1 reads
                 "...and a god was the Word" — the rendering JW apologetics cite.
  Pick deliberately. Default is `translation` (the proper edition text); choose
  `interlinear` if the point is to surface the "a god" reading.
"""
import argparse, json, re, sys

# English book name -> canonical 66-book number (NT only; matches import_json_bible CANON).
NT_BOOKS = {
    "matthew": 40, "mark": 41, "luke": 42, "john": 43, "acts": 44,
    "romans": 45, "1 corinthians": 46, "2 corinthians": 47, "galatians": 48,
    "ephesians": 49, "philippians": 50, "colossians": 51,
    "1 thessalonians": 52, "2 thessalonians": 53, "1 timothy": 54,
    "2 timothy": 55, "titus": 56, "philemon": 57, "hebrews": 58, "james": 59,
    "1 peter": 60, "2 peter": 61, "1 john": 62, "2 john": 63, "3 john": 64,
    "jude": 65, "revelation": 66,
}
ALIASES = {
    "apocalypse": 66, "revelation of john": 66, "revelations": 66,
    "first corinthians": 46, "second corinthians": 47,
    "first thessalonians": 52, "second thessalonians": 53,
    "first timothy": 54, "second timothy": 55,
    "first peter": 60, "second peter": 61,
    "first john": 62, "second john": 63, "third john": 64,
    "song of solomon": 22,  # harmless if an OT name ever appears; still NT-only overall
}


def book_num(name):
    key = re.sub(r"\s+", " ", str(name).strip().lower())
    return NT_BOOKS.get(key) or ALIASES.get(key)


def iter_books(books):
    """Yield {book, chapters} dicts whether `books` is one object, a list, or a name->obj map."""
    if isinstance(books, dict):
        if "chapters" in books:
            yield books
        else:
            for k, v in books.items():
                if isinstance(v, dict) and "chapters" in v:
                    yield {"book": v.get("book", k), "chapters": v["chapters"]}
    elif isinstance(books, list):
        for b in books:
            yield b


def main():
    ap = argparse.ArgumentParser(description="Convert Emphatic Diaglott JSON -> flat import JSON.")
    ap.add_argument("--input", required=True, help="the Diaglott JSON you're building")
    ap.add_argument("--output", required=True, help="flat JSON to feed import_json_bible.py")
    ap.add_argument("--field", default="translation",
                    choices=["translation", "interlinear", "greek"],
                    help="which per-verse field becomes the edition text (default: translation; "
                         "use interlinear to surface the 'a god' reading)")
    a = ap.parse_args()

    with open(a.input, encoding="utf-8-sig") as f:
        data = json.load(f)

    out, skipped, seen = [], 0, []
    for b in iter_books(data.get("books", data)):
        name = b.get("book", "")
        num = book_num(name)
        if num is None:
            sys.exit(f"ERROR: unrecognized book name {name!r} — add it to NT_BOOKS / ALIASES.")
        seen.append((num, name))
        for ch in b.get("chapters", []):
            cnum = int(ch["chapter"])
            verses = ch.get("verses", {})
            items = verses.items() if isinstance(verses, dict) else enumerate((v for v in verses), 1)
            for vkey, v in items:
                try:
                    vnum = int(vkey)
                except (TypeError, ValueError):
                    vnum = int(re.sub(r"\D", "", str(vkey)) or 0)
                text = re.sub(r"\s+", " ", (v.get(a.field) or "").strip())
                if not text:
                    skipped += 1
                    continue
                out.append({"book": num, "chapter": cnum, "verse": vnum, "text": text})

    out.sort(key=lambda r: (r["book"], r["chapter"], r["verse"]))
    with open(a.output, "w", encoding="utf-8") as f:
        json.dump({"verses": out}, f, ensure_ascii=False)

    bl = ", ".join(f"{n} ({num})" for num, n in sorted(set(seen)))
    print(f"Wrote {len(out)} verses to {a.output}")
    print(f"  books: {bl}")
    print(f"  text field: {a.field}")
    if skipped:
        print(f"  skipped {skipped} verse(s) with an empty {a.field!r} field")
    print("\nNext:")
    print(f"  python3 scripts/bibles/03_import_json_bible.py --allow-partial --input {a.output} "
          f"--format flat --id bible-diaglott-1864 --abbr Diag "
          f"--translation \"Emphatic Diaglott\" --lang en --iso3 eng --year 1864 "
          f"--license \"Public Domain\" --source \"public domain (Benjamin Wilson, 1864)\" --dry-run")


if __name__ == "__main__":
    main()
