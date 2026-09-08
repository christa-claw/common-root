# Common Root? — Technical Documentation

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.4 |
| Generated | 2026-09-08 |
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

## Module structure

```
religious-texts/                 (repo slug; app name is "Common Root?")
  app/             Vaadin reader application (port 8090)
  ingestion/       Ingestion pipeline (port 8091)
  schema/          Canonical XML schema (religious-text.xsd)
  docker/          docker-compose.yml, MySQL config, init SQL
  transcripts/     YouTube transcript pipeline + generated SQL
  docs/            Documentation sources and build pipeline
```

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
an open, no-rate-limit source: the Arabic Uthmani text and a public-domain English
translation (Pickthall) are loaded as paired editions, each ayah stamped with both
its canonical (mushaf) and chronological (revelation-order) sequence.

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
| Document | Language | Books | Verses |
|---|---|---|---|
| `bible-ar-vandyck` |  | 66 | 31102 |
| `bible-asv-1901` |  | 66 | 31102 |
| `bible-bes` |  | 66 | 31103 |
| `bible-bsb` |  | 66 | 31086 |
| `bible-de-1545` |  | 66 | 31170 |
| `bible-de-elberfelder` |  | 66 | 31102 |
| `bible-diaglott-il-1864` |  | 27 | 7955 |
| `bible-dra-1899` |  | 72 | 35598 |
| `bible-fbv` |  | 66 | 31104 |
| `bible-fi-1548` |  | 71 | 13616 |
| `bible-fi-1642` |  | 78 | 35545 |
| `bible-fi-1776` |  | 66 | 31102 |
| `bible-fi-1933` |  | 78 | 35438 |
| `bible-fr-darby` |  | 66 | 31167 |
| `bible-gnv-1599` |  | 66 | 31090 |
| `bible-grc-tr` |  | 27 | 7957 |
| `bible-he-delitzsch` |  | 66 | 31102 |
| `bible-he-wlc` |  | 39 | 23213 |
| `bible-hlt-olcim` |  | 66 | 31104 |
| `bible-irvhin-2019` |  | 66 | 31104 |
| `bible-it-diodati` |  | 66 | 31102 |
| `bible-kjv-1611` |  | 80 | 36820 |
| `bible-la-vulgate` |  | 73 | 35809 |
| `bible-lsv` |  | 66 | 31104 |
| `bible-lut1912-1912` |  | 66 | 31171 |
| `bible-nasb-2020` |  | 66 | 31073 |
| `bible-nbla` |  | 66 | 31090 |
| `bible-niv-2011` |  | 66 | 30752 |
| `bible-pddpt` |  | 66 | 31078 |
| `bible-ru-synodal` |  | 66 | 30266 |
| `bible-rv-1885` |  | 80 | 36873 |
| `bible-rvr09-1909` |  | 66 | 31102 |
| `bible-sv-1917` |  | 78 | 35350 |
| `bible-tr-ytc-2023` |  | 66 | 31059 |
| `bible-vbl` |  | 66 | 31102 |
| `bible-web` |  | 80 | 37839 |
| `bible-zh-cuv` |  | 66 | 31101 |
| `hadith-abudawud-ar` | ar | 43 | 5274 |
| `hadith-abudawud-en` | en | 43 | 5274 |
| `hadith-bukhari-ar` | ar | 98 | 7589 |
| `hadith-bukhari-en` | en | 98 | 7589 |
| `hadith-dehlawi-ar` | ar | 1 | 40 |
| `hadith-dehlawi-en` | en | 1 | 40 |
| `hadith-ibnmajah-ar` | ar | 38 | 4343 |
| `hadith-ibnmajah-en` | en | 38 | 4343 |
| `hadith-malik-ar` | ar | 62 | 1858 |
| `hadith-malik-en` | en | 62 | 1858 |
| `hadith-muslim-ar` | ar | 57 | 7563 |
| `hadith-muslim-en` | en | 57 | 7563 |
| `hadith-nasai-ar` | ar | 52 | 5765 |
| `hadith-nasai-en` | en | 52 | 5765 |
| `hadith-nawawi-ar` | ar | 1 | 42 |
| `hadith-nawawi-en` | en | 1 | 42 |
| `hadith-qudsi-ar` | ar | 1 | 40 |
| `hadith-qudsi-en` | en | 1 | 40 |
| `hadith-tirmidhi-ar` | ar | 49 | 3998 |
| `hadith-tirmidhi-en` | en | 49 | 3998 |
| `lds-book-of-mormon` | en | 15 | 6604 |
| `lds-doctrine-and-covenants` | en | 1 | 3654 |
| `lds-pearl-of-great-price` | en | 5 | 635 |
| `quran-ar-uthmani` | ar | 114 | 6236 |
| `quran-en-pickthall` | en | 114 | 6236 |
| `quran-en-yusufali` | en | 114 | 6236 |
| `quran-ru-sablukov` | ru | 114 | 6236 |

_64 translations, 1,227,312 verses total._
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
| Channel | Tradition | Content |
|---|---|---|
| Apologetics Roadshow | Christian | shorts,videos,streams |
| GodLogic Apologetics | Christian | shorts,videos,streams |
| Hatun Tash DCCI Ministries | Christian | shorts,videos,streams |
| Israel Advocacy | Christian | shorts,videos,streams |
| Shamounian Explains | Christian | shorts,videos,streams |
| The Crucible | Christian | shorts,videos,streams |
| JihadWatchVideo | Critical | shorts,videos,streams |
| Raymond Ibrahim | Critical | shorts,videos,streams |
| Ali Dawah | Islamic | shorts,videos,streams |
| DUS Dawah | Islamic | shorts,videos,streams |
| DawahWise | Islamic | shorts,videos,streams |
| Dr Zakir Naik | Islamic | shorts,videos,streams |
| Let the Quran Speak | Islamic | shorts,videos,streams |
| Mohammed Hijab | Islamic | shorts,videos,streams |
| Modern Day Debate | Neutral | shorts,videos,streams |
| Alpha & Omega Ministries | Unknown | shorts,videos,streams |
| Apologia Studios | Unknown | shorts,videos,streams |
| Bible Thinker | Unknown | shorts,videos,streams |
| Bob of Speaker's Corner | Unknown | shorts,videos,streams |
| Christ Over ALL | Unknown | shorts,videos,streams |
| Cross Examined | Unknown | shorts,videos,streams |
| DCCI Ministries | Unknown | shorts,videos,streams |
| Elijah Johnson Apologetics | Unknown | shorts,videos,streams |
| Jay Dyer | Unknown | shorts,videos,streams |
| Maybe God Podcast | Unknown | shorts,videos,streams |
| One God One Truth HQ | Unknown | shorts,videos,streams |
| SO BE IT | Unknown | shorts,videos,streams |
| Theological Apologia | Unknown | shorts,videos,streams |
| Towards Eternity | Unknown | shorts,videos,streams |
| Vlad Savchuk | Unknown | shorts,videos,streams |

_30 channels configured._
<!-- /AUTOGEN:channels -->

## Running the project

```bash
# 1. Databases
cd docker && docker-compose up -d        # wait until both report healthy

# 2. Reader application
cd app && mvn spring-boot:run            # http://localhost:8090

# 3. Ingestion pipeline (when needed)
cd ingestion && mvn spring-boot:run      # http://localhost:8091
curl -X POST http://localhost:8091/ingest/all
```

The reader's landing page is the About / Help page at `/`; the reader itself is
at `/reader`.

## Web API (v1) — specified, not yet built

An authenticated read-only API is specified and scheduled: `docs/api-design.md`
holds the design reasoning and decisions, `docs/api-spec.md` the buildable v1
specification, and `docs/api-test-cases.md` the test obligations. Summary:

Everything lives under `/api/v1`. Access requires an account and an API key
(`crk_…`, several per account, hash-at-rest, header-only); each key carries two
monthly allowances — one for requests, one for download bytes — with rate-limit
headers on every response and `429 + Retry-After` past the line. A key raises
capacity only, never entitlement: the download licence gate keeps reading each
text's own `@license` and knows nothing about keys.

| Route | Purpose |
|---|---|
| `GET /api/v1/health` | uptime signal; no auth, no quota |
| `GET /api/v1/texts` | edition list with metadata and downloadability |
| `GET /api/v1/texts/{src}` | one edition's metadata |
| `GET /api/v1/texts/{src}/download` | full public-domain edition as corpus XML |
| `GET /api/v1/texts/{src}/{ref}` | one chapter |
| `GET /api/v1/passages?src=&refs=` | scattered verses/ranges across books |
| `GET /api/v1/comments?ref=` | published comments by reference, no scripture |
| `POST /api/v1/verify` | authenticity check of content claimed to be from here |

Source tokens and references are the reader-link vocabulary (`kjv`, `JHN.1.1`,
`Q.2.255` — see *Shareable links*); response formats are per-call JSON, XML
(always a valid `religious-text.xsd` subtree, never an API dialect) or plain
text through the reader's display modes. Responses carry an attestation tag —
HMAC over a canonical form of the texts plus a server-held secret — so content
taken from the API can later be verified as authentic, for any past corpus
version, via the verify route.

Downloads are precomputed at corpus-publish time and streamed from disk with
the corpus hash as ETag, so bulk traffic never reaches BaseX. When the API
ships, the anonymous `/download` route is retired (410) and the About page's
download wording changes in all 13 language bundles.

## Documentation pipeline

These documents are living artifacts. The markdown files in `docs/src/` are the
single source of truth. Hand-written prose is edited directly; tables that drift
with the project (metadata, the translation/ingestion table, the channel list)
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
