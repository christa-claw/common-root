# Documentation

These documents are **living artifacts**, built from text sources — never edited
as binaries directly.

## Source of truth

```
docs/
  src/
    overview.md     <- edit this (high-level overview, was the PPTX/PDF)
    technical.md    <- edit this (architecture & technical reference)
  build_docs.py     <- the pipeline
  *.pdf *.docx *.pptx   <- GENERATED — do not hand-edit
```

Edit the markdown in `src/`. The PDF, DOCX and PPTX files are build outputs,
regenerated from the markdown. Treat them like compiled code.

## AUTOGEN blocks

Sections that drift as the project evolves are generated from project state, so
they never go stale. They live between markers:

```markdown
<!-- AUTOGEN:translations -->
...generated content, do not edit by hand...
<!-- /AUTOGEN:translations -->
```

Everything outside the markers is hand-written prose and is left untouched.

| Block | Source | Contents |
|---|---|---|
| `meta` | `pom.xml` | App name, version, build date |
| `translations` | live BaseX query | Per-translation book/verse counts |
| `channels` | `channels.properties` | Transcript channels and traditions |

To add a new auto-generated section: wrap a region in `<!-- AUTOGEN:name -->` /
`<!-- /AUTOGEN:name -->` markers and add a matching `gen_name()` function to the
`GENERATORS` dict in `build_docs.py`.

## Building

```bash
cd docs
python3 build_docs.py                  # refresh AUTOGEN blocks, then render all formats
python3 build_docs.py --refresh-only   # update the markdown AUTOGEN blocks only
python3 build_docs.py --render-only    # render current markdown, skip the refresh
python3 build_docs.py --formats pdf    # limit output formats (pdf,docx,pptx)
```

### Requirements

The build runs on a machine with these installed (e.g. the Mac, not a sandbox):

- **pandoc** — markdown to DOCX and PPTX
- **LibreOffice** (`soffice`) — DOCX to PDF
- **requests** (pip) — optional; only needed for the live BaseX `translations`
  block. Without it, that block is left with a note instead of failing.

If a renderer is missing, that format is skipped with a warning; the rest still
build. If BaseX is not running, the `translations` block keeps its previous
content and notes that it was not refreshed.

### BaseX connection

The `translations` block reads from BaseX using these environment variables
(defaults shown):

```
BASEX_URL=http://localhost:8984
BASEX_DB=religioustext
BASEX_USER=admin
BASEX_PASS=admin
```

## Output naming

| Source | Outputs |
|---|---|
| `src/overview.md` | `religious-texts-overview.{pdf,docx,pptx}` |
| `src/technical.md` | `religious-texts-technical.{pdf,docx,pptx}` |

Output filenames keep the `religious-texts-` prefix to match the existing
committed files and avoid churn; the document *titles* inside are "Common Root?".
