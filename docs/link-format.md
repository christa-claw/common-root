# Common Root? — Shareable Reader Links

Every reader view can be captured as a plain URL. The link is **human-readable
and hand-authorable** — you can build one yourself in a text editor, share it,
bookmark it, or generate it from a script. It carries no database id, so it
never expires.

A link describes one or more **columns**, each pointing at a text and a place in
it. Because references are stored as edition-independent codes, a link made in
one translation still opens correctly if the reader later views another.

```
https://common-root.org/reader?cols=2&sync=1
   &c1.src=kjv&c1.ref=SNG.2.16&c1.mode=titles&c1.order=chrono
   &c2.src=q-en&c2.ref=Q.2.255&c2.companion=1
```

(Locally the base is `http://localhost:8090/reader`.)

---

## Global parameters

| Param  | Values        | Meaning                                                        |
|--------|---------------|----------------------------------------------------------------|
| `cols` | `1`–`9`       | Number of columns. Capped at 9. Optional — inferred otherwise.  |
| `sync` | `1` (default) / `0` | Link the columns' scrolling. Only columns that share a reference system actually couple (see *Sync*). |

## Per-column parameters

Each column `K` (1-based) uses the prefix `cK.`:

| Param          | Required | Values                                              | Meaning                                  |
|----------------|----------|-----------------------------------------------------|------------------------------------------|
| `cK.src`       | yes      | a source token (below)                              | Which text the column shows.             |
| `cK.ref`       | no       | a reference (below)                                 | Where to open. Omit to start at the top. |
| `cK.mode`      | no       | `original` `continuous` `chapters` `verses` `titles`| Display mode. Defaults to `verses`.      |
| `cK.order`     | no       | `canon` (default) / `chrono` / `tanakh`             | Reading order.                           |
| `cK.hl`        | no       | comma-separated references                          | Extra passages to flash on landing, in any book. Does not affect where the column opens. Max 12. |
| `cK.companion` | no       | `1`–`9`                                             | Qur'an / hadith base columns: any value shows the paired companion translation beneath each ayah / hadith. Bible columns: opens the first N RUNGS of the edition's antecedent-translation lineage beneath each verse (see *Lineage rungs*). |

The **source token implies the column type**, so no separate type parameter is
needed.

---

## Source tokens

A token is the source's **abbreviation, lowercased** — the same abbreviation
shown on the source's About-listing card and in the reader's source picker.
Matching is case-insensitive, and every ingested text is addressable. The
families:

| Family | Examples | Notes |
|--------|----------|-------|
| Bibles | `kjv` `web` `niv` `asv` `dra` `rvr09` `sv1917` `syn` `cuv` `hebm` … | one token per edition |
| Original-language / historical editions | `wlc` (Hebrew), `tr` (Greek NT), `vul` (Latin), `kr3338` (Finnish 1933/38) | navigable via the canonical-seq stamping pass |
| Qur'an | `q-ar` (Uthmani base), `q-en` (Pickthall), `q-ya` (Yusuf Ali), `q-sab` (Sablukov) | the translations pair with `q-ar` as companions |
| Hadith | `buk-ar` / `buk-en`, likewise `mus` `abd` `tir` `nas` `ibm` `mal` `naw` `qud` `deh` | per-edition tokens: `<collection>-ar` is the Arabic matn base, `<collection>-en` its English translation (companion of the base) |
| LDS standard works | `bom` `dc` `pgp` | |

`comments` remains reserved. An unknown token falls back to the reader's
default Bible rather than failing.

These links are emitted by the app itself, not only hand-authored: the reader
toolbar's Copy-link, comment permalinks, and — since 2026-07-24 — every card
in the About page's "Available Texts" listing, which anchors to
`/reader?c1.src=<token>` (hadith cards add `c1.companion=1` so the English
shows beneath the Arabic).

---

## References

A reference is **edition- and language-independent** — it names the passage, not
a translation's wording.

### Bible

```
<BOOK>.<chapter>.<verse>
```

`BOOK` is the standard three-letter book code (table below), e.g. `JHN.3.16`,
`GEN.1.1`, `1CO.13.4`. The verse is optional: `JHN.3` opens the chapter.

A reference may be a **range**: `ISA.52.13-53.12` (chapter.verse after the
dash) or `PHP.4.10-13` (verse alone = same chapter). The column opens at the
start and the whole span is highlighted — a link can present a passage, not
just its first verse. A malformed or backwards end is dropped leniently,
keeping the start.

A range needs a start **verse**: the dash is only read when one is given, so
`DEU.33-34` is not a range — it opens Deuteronomy 33.

### Highlighting elsewhere: `cK.hl`

A `ref` names one book, so it cannot span from Deuteronomy to Joshua. `hl` is a
separate comma-separated list of references, painted with the same flash but
never scrolled to:

    /reader?c1.src=kjv&c1.ref=DEU.34.1-12&c1.order=chrono&c1.hl=PSA.90,PSA.91

This exists for reordered editions, where the passage worth pointing at is the
one that MOVED — which is by definition in a different book from the one the
link opens at. Widening `ref` into a cross-book range would have been forced to
paint the anchor as well, and the anchor is the part that did not move.

An entry with no verse (`PSA.90`) means the whole chapter; ranges work as in
`ref`. Unparseable entries are dropped rather than failing, and the list is
capped at 12 so a crafted link cannot make the client rescan for hundreds of
spans on every tick of the flash interval.

**Needs a numbered mode.** Highlighting finds verses by the `v-CODE-CH-VS` span
id, and the renderer only sets that id when verse numbers are shown. Under
`mode=original` or `mode=continuous` there is nothing to paint, so `hl` (and a
ranged `ref`) silently does nothing. Default `verses` is fine.

### Qur'an

```
Q.<surah>.<ayah>
```

e.g. `Q.2.255` (Al-Baqara, ayah 255). The ayah is optional: `Q.2` opens the
surah. The leading `Q` is what marks it as a Qur'an reference. Ranges work
within one surah (`Q.2.255-260`); a range that names a different surah after
the dash opens at the start with only that ayah highlighted.

### Other corpora

The LDS standard works use their own USFM-style book codes, unique across all
three works: `1NE.3.7`, `DC.76` (section = chapter), `MOSE.1.39`. Hadith
collections number their books/sections, so there is no stable alpha code to
hand-author — open a hadith column by `src` alone (top of the collection).

### Bible book codes

```
GEN Genesis        EXO Exodus         LEV Leviticus      NUM Numbers
DEU Deuteronomy    JOS Joshua         JDG Judges         RUT Ruth
1SA 1 Samuel       2SA 2 Samuel       1KI 1 Kings        2KI 2 Kings
1CH 1 Chronicles   2CH 2 Chronicles   EZR Ezra           NEH Nehemiah
EST Esther         JOB Job            PSA Psalms         PRO Proverbs
ECC Ecclesiastes   SNG Song of Songs  ISA Isaiah         JER Jeremiah
LAM Lamentations   EZK Ezekiel        DAN Daniel         HOS Hosea
JOL Joel           AMO Amos           OBA Obadiah        JON Jonah
MIC Micah          NAM Nahum          HAB Habakkuk       ZEP Zephaniah
HAG Haggai         ZEC Zechariah      MAL Malachi        MAT Matthew
MRK Mark           LUK Luke           JHN John           ACT Acts
ROM Romans         1CO 1 Corinthians  2CO 2 Corinthians  GAL Galatians
EPH Ephesians      PHP Philippians    COL Colossians     1TH 1 Thessalonians
2TH 2 Thessalonians 1TI 1 Timothy     2TI 2 Timothy      TIT Titus
PHM Philemon       HEB Hebrews        JAS James          1PE 1 Peter
2PE 2 Peter        1JN 1 John         2JN 2 John         3JN 3 John
JUD Jude           REV Revelation
```

---

## Modes and order

`mode` controls how the text is laid out:

| Token        | Shows                                                |
|--------------|------------------------------------------------------|
| `original`   | Scriptio continua — continuous uppercase, no breaks  |
| `continuous` | Continuous prose, modern spacing, no chapter/verse numbers |
| `chapters`   | Chapter headings, prose within                       |
| `verses`     | Chapter headings + verse numbers (**default**)       |
| `titles`     | Adds editorial section titles (where available)      |

Aliases accepted on input: `scriptio` → `original`, `simple` → `continuous`,
`numbered` → `verses`. For Qur'an and hadith columns only `continuous` and
`verses` ("Numbered ayat") apply; anything else falls back to `verses`.

`order` is `canon` (the traditional order — the mushaf for the Qur'an),
`chrono` (chronological / order of revelation), or `tanakh` (the Hebrew Bible
arrangement: Torah → Nevi'im → Ketuvim, ending at Chronicles, with the NT
unchanged after it). A source with no `@globalTanakhSeq` falls back to
canonical rather than failing.

---

## Sync

`sync=1` links scrolling, but columns only truly couple when they share a
reference axis: two Bible editions follow each other, and two Qur'an editions
follow each other. A Bible column and a Qur'an column have no shared verse axis,
so they scroll independently even with `sync=1`.

---

## Examples

Single verse, one translation:
```
/reader?cols=1&c1.src=kjv&c1.ref=JHN.3.16
```

Compare two translations of the same verse, scrolling together:
```
/reader?cols=2&sync=1&c1.src=niv&c1.ref=ISA.53.5&c2.src=kjv&c2.ref=ISA.53.5
```

Arabic Qur'an with the English translation beneath each ayah:
```
/reader?cols=1&c1.src=q-ar&c1.companion=1&c1.ref=Q.2.255
```

Read John in chronological order, titles shown, beside the Qur'an:
```
/reader?cols=2&sync=0&c1.src=niv&c1.ref=JHN.1&c1.mode=titles&c1.order=chrono&c2.src=q-en&c2.ref=Q.19
```

---

## Lineage rungs

A Bible column can show, beneath each verse, the translations the edition
stands on — `@basedOn` chains curated in `patch_lineage.py` (WEB → ASV → RV →
KJV → Geneva → TR/WLC, DRA → VUL, Kirkkoraamattu → Biblia 1776 …), walked a
rung at a time in ladder order: generation by generation, main line first
within each. `cK.companion=N` opens the first N rungs; the reader's ▾/▴
controls step content-aware (rungs with no text for the current book are
skipped forward, e.g. the Greek NT while reading Genesis). Rungs are
VERSE-GRANULAR: they render only in the Verses and Titles modes — Original,
Continuous and Chapters present unbroken flow, so there the rung lines and
controls are suppressed and the depth quietly persists until a verse-granular
mode returns.

Every edition's ladder ends on an ORIGINAL-LANGUAGE FLOOR: the corpus's
witnesses of the Hebrew (WLC) and the Greek (TR), added even where no chain is
recorded. Floor rungs render clearly MARKED (≈ prefix, dashed chip, tooltip)
because they are witnesses, not attested sources — critical-text translations
(NIV …) do not translate from the TR. Attested rungs render plain. Rungs align
across editions (and languages) by book-code/chapter/verse — canonical seqs
are per-edition and never compared between editions.

---

## Robustness

The parser is deliberate about being forgiving, which is what makes hand-written
links safe:

- Unknown tokens, malformed references, and out-of-range values fall back to
  sensible defaults rather than failing.
- More than 9 columns is capped at 9.
- A column with no `src` is skipped.
- Old links keep working as the app gains new texts and modes.
