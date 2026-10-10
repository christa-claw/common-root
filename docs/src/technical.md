# Common Root? — Technical Documentation

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.9.6 |
| Generated | 2026-10-10 |
<!-- /AUTOGEN:meta -->

> **Naming note.** The user-facing application is **Common Root?**. Internal
> identifiers retain the original `religious-texts` / `religioustext` slug — the
> GitHub repository, the Java package `org.religioustext`, the Vaadin theme
> folder, the BaseX database name, and the `religioustext-mysql` Docker
> container. This is deliberate: renaming infrastructure identifiers carries
> risk and no user-visible benefit.

## Architecture

Common Root? is a Maven multi-module project with two Spring Boot applications
backed by three data stores — two databases and a derived search index — all of
which run in Docker.

```
Ingestion Pipeline (port 8091)        Reader Application (port 8090)
  Spring Boot                           Spring Boot + Vaadin 24
  ApiBibleCrawler                       ReaderView (multi-column)
  LocalBibleReader                      AboutView (landing page)
  IngestionService                      TextQueryService · SearchService
        |                                     |
        v                                     v
  BaseX XML DB (port 8984)            MySQL (port 3306)
  Scripture texts                     User accounts, comments,
  One document per translation        personal notes

                                      Meilisearch (port 7700, internal only)
                                      Unified full-text index over all
                                      editions + public comments
```

The reader queries BaseX for scripture text, MySQL for user comments and
personal notes, and Meilisearch for full-text search (see *Full-text search*
below — the index is derived data, rebuilt from BaseX and MySQL by
`reindex_search.py`). The ingestion pipeline writes only to BaseX.

Local development runs only BaseX and MySQL; Meilisearch exists only in
production, where it is populated from source by the reindexer. Production adds a
Caddy reverse proxy and a self-hosted Umami analytics instance (see *Production
deployment*).

## Module structure

```
religious-texts/                 (repo slug; app name is "Common Root?")
  app/             Vaadin reader application (port 8090), Flyway migrations
  ingestion/       Ingestion pipeline (port 8091)
  schema/          Canonical XML schema (religious-text.xsd)
  docker/          compose files (dev and prod), Caddyfile, MySQL config, init SQL
  scripts/         ingestion and stamping (bibles/), audio, channels, publish, ops, print
                   (print-edition builders)
  automation/      scheduled-job entry points (e.g. the nightly audio run)
  sources/         raw source texts and their conversions
  orderings/       chronological and writing-order tables (YAML)
  fonts/           fonts used for print output
  transcripts/     YouTube transcript pipeline and the argument ledger
  docs/            Documentation sources and build pipeline
```

Requirements: JDK 21 and Maven for the Java modules, Docker for the data stores,
Python 3 for the ingestion and publishing scripts.

## Data model

### Scripture (BaseX)

Each translation is stored as **one XML document** named `{id}.xml` (for example
`bible-niv-2011.xml`), with books held in canonical order. Queries open a
document directly by name rather than searching across the whole database:

```xquery
db:open('religioustext', 'bible-niv-2011.xml')/rt:text/rt:book
```

The schema nests `text > book > chapter > verse` for the Bible, and
`text > chapter > verse` for the Quran. Each verse carries ordering attributes —
`globalCanonicalSeq` and `globalChronologicalSeq` — that drive the reader's
canonical and chronological reading orders. The reader addresses a verse *by its
sequence number*: a reference resolves to a `globalCanonicalSeq`, and the window
is rendered at or after that position — position **is** the sequence. Both
attributes must therefore be populated on every verse of every edition (see
*Sequence stamping* under Scripture sources). A missing `globalCanonicalSeq` does
not error; it silently collapses the verse to a shared fallback position, so an
edition where the attribute was never stamped opens at an arbitrary verse rather
than the one requested. The chronological order interleaves passages across
books, so the prophets and psalms read in their historical setting. (A third
attribute, `globalNarrativeSeq`, is reserved in the schema for a future narrative
ordering and is not currently surfaced in the reader.)

**Data integrity rule:** if stored data looks wrong, the fix belongs in the
ingestion pipeline followed by re-consolidation — never in a defensive query.
Diagnostic and consolidation helpers (`inspect_basex.py`, `consolidate_basex.py`)
live in the project root.

### Users & comments (MySQL)

User accounts, comments, comment-to-verse references, and personal notes live
in MySQL. Comment references can be translation-independent
or pinned to a specific translation, which lets a commentary link to the exact
wording it is arguing about (for example, distinguishing how Isaiah 7:14 is
rendered across translations).

Access control follows a two-plane, Documentum-inspired model (see
`docs/access-control.md`): a cumulative role ladder (consumer → contributor →
admin → superuser) co-existing with per-object ACLs whose accessors are users,
groups, and the specials (world / signed-in / owner). Each imported channel's
comments are governed by its org's default ACL; admins manage the shared ACLs
at `/admin/acls`, and per-comment overrides are copy-on-write from the comment
itself.

Commenting is one unified editor — content plus the cited passages (chips with
a free-text parser) and an external video link — used for composing and for
editing. New comments are private-first: they publish only when the author
ticks Public (contributor role required). A signed-in reader's own comments,
including private drafts, surface under every verse they cite; an edit button
appears wherever the caller's effective ACL level reaches write (channel
members on their org's imported arguments, owners, admins).

## Shareable links

The reader serializes its full multi-column state into the page query string, and
parses the same grammar on entry to rebuild the view. The grammar is documented in
`docs/link-format.md`; in brief:

- `cols=N` and a global `sync=0|1`, then per-column `cK.src` (required),
  `cK.ref`, `cK.mode`, `cK.order`, and `cK.companion`.
- `src` is the source's abbreviation (`niv`, `kjv`, …, `q-ar`, `q-en`); the token
  implies the text's type.
- `ref` is edition- and language-independent: `BOOK.chapter.verse` in USFM codes
  for the Bible (e.g. `SNG.2.16`), or `Q.surah.ayah` for the Quran (e.g. `Q.2.255`).

Parsing is lenient — columns are capped, each `src`/`ref` is validated, and
anything unrecognised is dropped — so hand-authored links and links from older
versions degrade gracefully. The same reference vocabulary is what a stored
comment anchors to, so a comment and a link describe a passage the same way.

The link's clipboard copy runs client-side within the button's own click event,
so it works in browsers that restrict clipboard access outside a user gesture
(including Safari over plain HTTP).

## Interface localization

The reader interface ships in **14 languages**: English, Arabic, Spanish, Finnish,
Swedish, Russian, Chinese, French, Italian, German, Hindi, Hebrew, Turkish and
Japanese. Each language is a `translations_<code>.properties` bundle under
`app/src/main/resources/i18n/` beside the English base, and the supported set is
declared once in `LocaleUtil`. Arabic and Hebrew render right-to-left. The language
selector shows each language's flag and native name, and book names, reference
parsing and the social-preview tags follow the chosen language.

Every language has its own path (for example `/reader/fi`, `/read/zh`), and the
`?lang=` query parameter is accepted as well. Edition information pages exist in
every interface language.

The interface strings were drafted with AI assistance. Native-speaker review is
being carried out language by language; the first round, in Hebrew, corrected
several terms and found a flaw in the English source text. This applies to the
interface only: the scripture texts are existing published translations.

## Schema migrations

MySQL schema changes are versioned **Flyway** migrations (`V1` through `V19` at
the time of writing) under `app/src/main/resources/db/migration/`, applied at
startup. JPA schema handling is set to `validate`, so the application checks the
schema against its entities rather than altering it.

## Full-text search

A single **Meilisearch** container (on the internal Docker network only, never
published) provides one unified search index over **all ingested editions** —
every Bible, Qur'an, Hadith and LDS edition — plus the public comment corpus. The
engine was chosen over Elasticsearch for footprint (hundreds of MB vs. 1 GB+
idle) and over per-store search (MySQL `FULLTEXT` + BaseX `ft:search`) because the
whole point is *one* index queried as a unit, with per-language tokenization
across en/ar/he/fi/grc/la.

`reindex_search.py` (repo root) populates the index: scripture documents from
BaseX, comment documents from MySQL. Two design points carry the feature:

- **Edition-unique document id.** The id is `{source_id}_{book}_{ch}_{vs}`,
  sanitised so any character outside `[A-Za-z0-9_-]` becomes `_` (hadith verse
  "numbers" like `402.2` would otherwise break the id). A separate `ref` field
  keeps the original dotted form for filtering and collapsing. The earlier
  `{book}_{ch}_{vs}` id collided across editions and silently overwrote rows.
- **Collapse by verse.** The search service queries with Meilisearch
  `distinct:"ref"`, so a verse that exists in twenty editions returns as one row,
  not twenty. Selecting a row opens a translation picker (one checkbox per edition
  that has the verse); ticking two or more opens them as synced compare columns.
  The picker shows each edition's full name, resolved from the reader's live
  source list rather than stored in the index.

The index is **derived data** — rebuildable from BaseX (scripture) + MySQL
(comments) — so it follows the same governance rule as the rest of the corpus:
prod owns the live data, the reindexer runs on prod against prod's own stores, and
only comments (born online) are irreplaceable. Comment writes and moderation
transitions update the index in the same flow; a scripture reindex is scoped to
`kind != comment` so it never touches a comment document. On the 4 GB host the
full index sits at roughly 1.2 GB steady-state; `MEILI_MAX_INDEXING_MEMORY` is
capped in the prod compose so the one-shot indexing spike cannot exhaust RAM. The
full design note, including the facet model and the governance rules, is in
`docs/search.md`.

### Multi-reference quick-open

The reader toolbar has a reference-list field: a comma-separated list of passages
("`John 1:1, Rom 9:5, Col 1:15-17, 2 Pet 1:1`") opens each in its own column on
Enter. `RefListParser` matches each token against a USFM alias table (full book
names and common abbreviations, with or without a leading numeral and trailing
dots), emitting the same edition-independent reference the search picker and
comment links use; a verse range opens at its first verse, a chapter-only token
opens at the chapter, and unrecognised tokens are skipped. The lexicon is
English/USFM only for now — localized book-name input is a follow-up.

## Scripture sources

Bible text is ingested from three kinds of source:

1. **API.Bible** (`scripture.api.bible`) — licensed and richly-structured
   translations, including chapter titles. Subject to a monthly request quota.
2. **Local clone** of an open-source Bible corpus — public-domain translations,
   with chapter titles supplied from API.Bible where available.
3. **JSON edition import** (`import_json_bible.py`) — public-domain editions in
   many languages (Hebrew, Greek, Latin, Finnish, German, French, Italian,
   Swedish, Spanish, Russian, Chinese, …) loaded from per-edition JSON. Most of
   the original-language and non-English editions entered the corpus this way.

The ingestion controller routes each translation to the correct path based on
its configured source. The Quran is ingested separately (`ingest_quran.py`) from
an open, no-rate-limit source. The Arabic Uthmani text is the base edition and the
translations load as paired editions: Pickthall (English) and Sablukov (Russian,
1878) are public, and a further English edition is loaded but held back from the
public list pending a rights review. Each ayah is stamped with both its canonical
(mushaf) and chronological (revelation-order) sequence.

**Hadith.** Ten collections (Bukhari, Muslim, Abu Dawud, Tirmidhi, Nasa'i, Ibn
Majah, Malik, and the Nawawi, Qudsi and Dehlawi forty-hadith sets) are ingested by
`03_ingest_hadith.py` from the open fawazahmed0/hadith-api dataset. Each collection
is one Arabic base edition with an English companion, in the same schema as the
other texts: a collection is a `text`, a section is a `book`, a hadith is a `verse`.
The two languages align by `globalCanonicalSeq`, so the reader's companion picker
shows the English beneath the Arabic exactly as it does for the Quran.

**LDS standard works.** The Book of Mormon, Doctrine and Covenants and Pearl of
Great Price are ingested by `03_ingest_bom.py` as three standalone documents in the
Bible's `book > chapter > verse` shape (Doctrine and Covenants uses one book whose
chapters are its sections). They carry a canonical sequence only; no single
chronological ordering is imposed on them.

### Sequence stamping

Because the reader navigates by `globalCanonicalSeq` (see *Data model*), every
verse of every edition must be stamped. The full pipeline for a JSON-imported
edition is:

```
import_json_bible.py  ->  consolidate_basex.py  ->  stamp_canonical.py  ->  stamp_chronological.py
(verses + book/chapter    fixes book-level           dense 1..N              chronological
 attributes)              @canonicalOrder            @globalCanonicalSeq     @globalChronologicalSeq
```

`stamp_canonical.py` walks each edition's books in `@canonicalOrder`, then
chapters by number, then verses in document order, assigning a dense `1..N`
`@globalCanonicalSeq` in one XQuery update; it is idempotent and takes `--id`,
`--all-unstamped`, and `--dry-run`. The values need only be monotonic *within* an
edition — the reader always resolves a reference against the column's own edition
— so they need not align across editions.

The Java ingestion path (the API.Bible / local-clone Bibles) and the Qur'an
ingester stamp canonical sequence themselves. The JSON-import path historically
did **not**, which left twenty editions with no `@globalCanonicalSeq` and made
them open at an arbitrary verse instead of the one requested. `stamp_canonical.py`
closed that gap and is now a required step after any JSON import.

### Ingestion status

The following reflects the current state of the BaseX database at the time this
document was generated:

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

## Transcript & argument pipeline

A separate pipeline collects argument material about specific verses from public
video transcripts, for display alongside the text.

- **`fetch_transcripts.py`** downloads subtitle (VTT) files and metadata
  (`.info.json`) per channel using yt-dlp, with rate-limit-friendly delays and a
  download archive so re-runs skip what is already fetched.
- **`scripts/channels/02_extract_ctl.sh`** drives the local, zero-cost extractor
  (`extract_arguments_ollama.py`, gemma4 via Ollama): it reads each transcript,
  identifies distinct arguments and their verse references, and appends them to
  **`transcripts/arguments.json`** — the resumable ledger. (An Anthropic-API
  alternative, `extract_arguments.py`, exists for paid runs.)
- **`transcripts/enrich_arguments.py`** post-processes the ledger in place:
  resolves each entry's YouTube video id/URL and per-reference timecodes, so a
  comment can deep-link to the moment a verse is discussed.
- **`DataSeeder`** (in the app) re-seeds MySQL from `arguments.json` at every
  startup, deterministically replacing the previous system-user seed. Entries
  without a resolved source (video) link are skipped — auto-generated comments
  must carry their source. The file is git-tracked and baked into the app image,
  so a deploy is what publishes a new comment batch.

(`generate_arguments_sql.py`, which emitted SQL INSERTs from transcript
filenames, is the legacy path and is retained only for reference.)

Channels are configured in `channels.properties`, each tagged with a tradition
or format:

<!-- AUTOGEN:channels -->
| Tradition | Channels |
|---|---|
| Christian | 6 |
| Islamic | 6 |
| Critical | 2 |
| Neutral | 1 |
| Not yet classified | 17 |

_32 channels configured._
<!-- /AUTOGEN:channels -->

## Chapter audio

A fourth data plane, independent of the app, BaseX and the argument pipeline.

**What is produced.** One mp3 per chapter plus a sibling JSON of per-verse
millisecond offsets, generated by `scripts/audio/tts_build.py` from the verse text
in BaseX via Azure neural TTS. Files are named `<zero-padded chapter>_<BOOK>.<ext>`
and laid out as `<text-id>/<voice>/<BOOK>/`, so a lone downloaded file still says
what it is and a listing sorts 1, 2, 10 correctly. The voice sits in the path
deliberately: the files are served immutable with a one-year cache, so re-voicing
an edition must produce new URLs rather than stale hits.

**Where it lives.** Outside the working tree, in a directory this machine
mounts separately (configurable via `COMMONROOT_AUDIO_INDEX`) — roughly 1.7 GB
per Bible-sized edition, and the scheduled jobs abort on a dirty tree. Credentials (`tts.env`) sit beside that directory, never inside it: the whole
audio tree is public under `/audio/*`, so a secret placed there would be
downloadable.

**How prod serves it.** `./audio` on the host is bind-mounted read-only into two
containers: Caddy serves the mp3s straight off disk at `/audio/*`, never touching
the app or a session, and the app mounts the same tree at `/srv/audio` purely to
read `index.json`. The mp3s are never served through the JVM.

**The manifest contract.** `index.json` is rebuilt from what is actually on disk at
the end of every run, and `AudioIndexService` re-reads it whenever its mtime
changes, checked at most once a minute. A chapter entry must carry a `file` key or
the reader will not offer it — no file name, no audio — so a manifest written by an
older generator silently disables audio rather than 404ing.

**Rationing.** The generator works `scripts/audio/tts_queue.tsv` strictly top to
bottom and keeps a ledger at `scripts/audio/tts_ledger.json`. Free tier is always
spent first; `PAID_CAP` is a lifetime ceiling on billed characters and unset means
zero, so the job cannot spend money unless that variable is deliberately set. A run
also has its own character limit, so a night generates a handful of chapters. A
chapter already in the ledger and on disk is never regenerated.

**Long chapters.** A chapter longer than the segment limit is synthesised in
segments split at verse boundaries and joined with ffmpeg into one mp3 with one
offsets file; later segments' offsets are shifted by the joined file's measured
duration. The limit is 7,500 characters by default and lower for denser scripts,
because a long request in those scripts can be cut off: Chinese 2,000, Hebrew
2,400, Japanese 2,500, Arabic 2,800, Russian 5,000, German and Spanish 6,800.
Speaking rate is set per voice rather than per language, since it is the voice
that varies; a voice with no entry emits unchanged SSML.

**Operations.** Runs unattended via `com.commonroot.nightly-tts.plist`, logging to
`~/Library/Logs/commonroot/nightly_tts.log`. The runbook — authority for the job,
including its hard rules on budgets — is `docs/nightly-tts.md`.

## Running the project

```bash
# 1. Data stores (BaseX + MySQL; Meilisearch is production-only)
docker compose -f docker/docker-compose.yml up -d   # wait until both report healthy

# 2. Reader application
cd app && mvn spring-boot:run            # http://localhost:8090

# 3. Ingestion pipeline (when needed)
cd ingestion && mvn spring-boot:run      # http://localhost:8091
curl -X POST http://localhost:8091/ingest/all
```

The reader's landing page is the About / Help page at `/`; the reader itself is
at `/reader`. Requires JDK 21, Maven and Docker.

## Web API (v1)

A read-only, key-authenticated API lives under `/api/v1`, with its reference at
`/api/docs` and a Postman collection linked from it. Design reasoning is in
`docs/api-design.md`, the specification in `docs/api-spec.md` and the test
obligations in `docs/api-test-cases.md`. Reading the site itself needs no account;
the API is not a reading surface.

**Keys and quotas.** Access requires an account and an API key (`crk_…`, hashed at
rest, shown once, up to five live per account, managed from the profile page).
Keys travel in a header only; a key in a query string is refused even when valid.
Each key carries two monthly allowances (requests and download bytes, calendar
month UTC) plus a per-key burst limit, with `X-RateLimit-*` headers on every
metered response and `429 + Retry-After` past the line. A key raises capacity only,
never entitlement.

| Route | Purpose |
|---|---|
| `GET /api/v1/health` | uptime signal, no key; 200 or 503 with a per-subsystem breakdown |
| `GET /api/v1/texts` | edition list with metadata |
| `GET /api/v1/texts/{token}` | one edition's metadata |
| `GET /api/v1/texts/{token}/books` | table of contents, with the chapters that actually exist |
| `GET /api/v1/texts/{token}/{ref}` | one chapter or passage, as JSON or corpus XML |
| `GET /api/v1/passages?refs=` | scattered verses across books and editions, e.g. `kjv:1JN.5.7,web:1JN.5.7` |
| `GET /api/v1/texts/{token}/download` | a whole edition as corpus XML, metered against the byte allowance |
| `POST /api/v1/verify` | authenticity check of a saved response |

Tokens and references are the reader-link vocabulary (`kjv`, `JHN.3.16`, `Q.2.255`
— see *Shareable links*). JSON verse text is the reader's display text; XML is the
raw corpus element and is always a valid `religious-text.xsd` subtree, never an API
dialect.

**Licence gate.** Licensed editions (NIV, NASB and others) answer 403 on every
text route whatever key is presented, so the About page's statement that they are
not redistributed outside the platform holds for the API too. Edition metadata
stays open.

**Attestation.** Every passage response carries an HMAC over a canonical form of
the texts (edition, reference, cleaned verses, corpus label, mint time and build)
rather than over the wire bytes. A saved response can later be checked with
`POST /verify`, which answers `valid` plus `corpus: current | superseded` and never
says why a bundle failed. XML responses embed the tag as a processing instruction,
so a saved file is self-validating. Whole-edition downloads carry no tag: a
31,000-verse bundle cannot be verified within the `/verify` limits, so chapters and
passages remain the attested surface.

**Downloads.** A download is served with a strong ETag of the form
`"{id}@{corpusHash}"`, so a repeat request with `If-None-Match` returns 304 and
counts nothing. The earlier anonymous `/download/{key}` route is retired and
answers 410 Gone with a pointer to the API.

## Production deployment

Production runs as Docker services behind **Caddy**, which terminates TLS and
serves the audio files straight from disk (see *Chapter audio*). The services are:

- **BaseX** — an image published to GHCR with the corpus baked in.
- **MySQL 8.3** — the stock image, with its data on a volume.
- **Meilisearch** — pinned to a specific version, internal network only, with its
  indexing memory capped for the 4 GB host.
- **App** — an image published to GHCR and selected by version tag, so the running
  version is visible in `docker compose ps`.
- **Umami** — self-hosted, first-party analytics on its own subdomain; no
  third-party analytics scripts.

BaseX and the app ship together: the app reads its source catalog at boot, so the
BaseX image is deployed first and the app restarted after.

**Releases.** The version lives in the module poms; between releases it carries a
`-SNAPSHOT` suffix, and a snapshot is never promoted to production. CI builds
production images from the release tag and refuses a snapshot build unless
explicitly overridden, in which case it is never tagged as the latest image. Each
release regenerates this documentation, is tagged, and is exported to a public
mirror (Apache-2.0) as a single commit from an allow-list of paths, after a sweep
for anything that must not leave.

## Documentation pipeline

These documents are living artifacts. The markdown files in `docs/src/` are the
single source of truth. Hand-written prose is edited directly; tables that drift
with the project (metadata, the translation/ingestion table, the channel summary)
are regenerated from project state — `pom.xml`, `channels.properties`, and a live
BaseX query — and injected between `AUTOGEN` markers.

```bash
cd docs
python3 build_docs.py                 # refresh autogen blocks + render all formats
python3 build_docs.py --refresh-only  # update markdown only
python3 build_docs.py --render-only   # render current markdown only
```

Rendering produces PDF, DOCX and PPTX via pandoc and LibreOffice, which must be
installed on the build machine. See `docs/README.md` for details.

## API reference (Javadoc)

This document describes the system at the architectural level; the class- and
method-level contract lives in the source as Javadoc. The Java is documented to a
consistent standard: every class carries `@author`, `@version` and `@since`, and
methods use inline tags (`{@code}`, `{@link}`, `{@literal}`, `{@snippet}`) with
`@param`/`@return`/`@throws` written wherever a parameter or result is at all
ambiguous. The access-control subsystem — `AccessService` (the two-plane decision
point), `AclService`, `BasicLevel` (the permission ladder and owner floor), `Role`,
`Ace` and `Acl` — is the most thoroughly documented and is the recommended entry
point for a reader coming from the *Users & comments* and security sections above.

The running application publishes the full API reference at **`/docs/api/`** — the
`maven-javadoc-plugin` generates it into the jar's served resources during
`mvn package`, alongside these PDFs, so anyone wanting the class-level detail can
browse it live. The About page links it next to this document.

To generate and browse it locally from the app module:

```bash
mvn -q -pl app javadoc:javadoc
open app/target/reports/apidocs/index.html   # or target/site/apidocs on older plugin versions
```

`mvn -q -pl app javadoc:jar` produces the same HTML as a distributable
`…-javadoc.jar`. The doclint profile is `all,-missing`, so broken `{@link}`
references, malformed HTML and unknown tags fail the build, while the noisy
"missing comment" category (undocumented accessors and record components) is
suppressed.

## Security checklist (before any public deployment)

- Change the MySQL and BaseX credentials from their development defaults.
- Rotate the API.Bible key.
- Ensure no real secrets are committed (local secrets live in gitignored
  `application-local.properties`).
- Set JPA schema handling to `validate` (not `update`) in production.
- Put the database ports (MySQL 3306, BaseX 8984) behind a firewall — they
  should not be publicly reachable.
- Set a strong `MEILI_MASTER_KEY` in the production environment file.
