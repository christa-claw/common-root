#!/usr/bin/env bash
# Extract all 27 NT books of the Emphatic Diaglott TRANSLATION column to draft JSON.
#
# Page ranges come from `--mode headers` (the running-header book map); the
# alphabetical appendix at p840+ is NOT scripture and is excluded. Each book is a
# DRAFT from dirty OCR — proofread against the PDF before importing.
#
# IMPORTANT: pause the Ollama transcript extractor first — pdfplumber over ~830
# pages competes with gemma for CPU and can wedge the MCP bridge:
#     scripts/channels/02_extract_ctl.sh stop      # resume with `start` when this finishes
#
# Usage:
#     scripts/bibles/01_extract_diaglott_nt.sh ~/Downloads/diaglott.pdf ~/Downloads/diaglott-nt
# Then per book: scripts/bibles/03_import_json_bible.py --allow-partial --format flat ...
set -euo pipefail

PDF="${1:?usage: extract_diaglott_nt.sh <diaglott.pdf> <output-dir>}"
OUT="${2:?usage: extract_diaglott_nt.sh <diaglott.pdf> <output-dir>}"
mkdir -p "$OUT"

# book name (must match NT_BOOKS in extract_diaglott_pdf.py) | first page | last page
books=(
  "Matthew|11|117"          # already hand-built earlier; re-extract for a consistent draft
  "Mark|118|184"
  "Luke|185|296"
  "John|297|381"            # John 1:1 on p297-298 — check "the LOGOS was God" in the translation col
  "Acts|382|489"
  "Romans|490|534"
  "1 Corinthians|535|579"
  "2 Corinthians|580|607"
  "Galatians|608|622"
  "Ephesians|623|638"
  "Philippians|639|649"
  "Colossians|650|659"
  "1 Thessalonians|660|668"
  "2 Thessalonians|669|673"
  "1 Timothy|674|684"
  "2 Timothy|685|693"
  "Titus|694|698"
  "Philemon|699|701"
  "Hebrews|702|734"
  "James|735|745"
  "1 Peter|746|757"
  "2 Peter|758|765"
  "1 John|766|777"
  "2 John|778|779"
  "3 John|780|781"
  "Jude|782|784"
  "Revelation|785|839"
)

for entry in "${books[@]}"; do
  IFS='|' read -r book start end <<< "$entry"
  slug=$(echo "$book" | tr '[:upper:] ' '[:lower:]-')
  echo "=== $book (p$start-$end) ==="
  python3 scripts/bibles/01_extract_diaglott_pdf.py --pdf "$PDF" --mode extract \
    --book "$book" --start-page "$start" --end-page "$end" \
    --output "$OUT/diaglott-$slug.json" --debug "$OUT/diaglott-$slug.debug.txt"
done

echo
echo "Done. Drafts in $OUT/ — PROOFREAD against the PDF before importing."
echo "Watch the 'chapters detected' line per book: it should match the canonical count."
