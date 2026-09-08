# Common Root API v1 — draft specification

Drafted 2026-09-02, expanding `api-design.md` (2026-08-27) into a buildable
shape. Revised same week after the maintainer's review: added scattered-reference
retrieval (§3.5) and content attestation with a verify method (§3.7) — both at
her request, so their *existence* is settled; their mechanics are this draft's
proposals like everything else. **Status: phases 0, 1, 3, 4 built (see §0); phase 2 pending. Nothing here is ratified beyond what
`api-design.md` already marks Decided.** Decided items are carried through
unchanged and marked ⚖; everything else is this draft's proposal, written to be
argued with. Where an open question from the design notes is answered here, the
answer is a *recommendation* — §10 collects them so they can be accepted or
rejected one by one.

Companion reading: `api-design.md` (the reasoning), `link-format.md` (tokens and
references, reused wholesale), `access-control.md` (the role ladder keys hang
off), `CorpusDownloadController` (the licence gate and strip logic this spec
inherits).

---

## 0. As built (2026-09-06) — where the implementation departs from the draft below

Phases 0, 1, 3 and 4 are built and running locally; the sections below are
the design as drafted and stay as the reasoning. Where the build settled a
shape differently, **this section wins**. Consumer-facing documentation is
the served page `GET /api/docs` (source: `app/src/main/resources/api-docs.html`).

- **Edition identity is the token** (`kjv`, `web`, `q-ar`) everywhere the API
  speaks and everywhere the attestation signs — the link format's permanent
  address, not the document id. Responses carry `"text": "web"` as a string;
  the full edition record is one call away at `GET /texts/web`.
- **Drill-down is three calls:** `GET /texts/{token}` (the record),
  `GET /texts/{token}/books` (table of contents: code, name, native name,
  chapter numbers actually present — surahs for a Qur'an, books for a hadith
  collection), `GET /texts/{token}/{ref}` (text). `/books` is new, §3 did not
  have it.
- **Passages carry their edition each.** `/passages` has no header object:
  `{ passages: [ { text, direction, ref, book, chapter, verses, found }… ],
  attestation }`. A reference may be prefixed `kjv:1JN.5.7`; `src` is the
  default for unprefixed ones and may be a list (`src=kjv,web`), in which case
  an unprefixed reference expands across every edition in it. Cap: 20
  passages after expansion.
- **One attestation per response, over all the texts in it.** The canonical
  form (§3.7) is `canon`, `corpus`, then per passage `text`, `ref`, verse
  lines — in served order. A cross-edition comparison verifies as one object;
  reorder, drop or relabel a passage and it fails. `direction` is a top-level
  field on every JSON passage response (plain text cannot carry it).
- **JSON verse text is the reader's display text** (`VerseRef.clean()`: no ¶,
  no §, no appended footnotes). XML is the raw corpus element. The canonical
  form is over the cleaned text, so JSON and XML of one passage carry the
  same tag; an XML consumer cleans before verifying.
- **Verify accepts both shapes:** `{text, ref, verses}` or
  `{passages:[{text, ref, verses}]}`, plus `attestation`. Still behind the key
  (§10.8 remains the maintainer's call).
- **Built 2026-09-08 (0.8.5):** `GET /texts/{token}/download` — XML only,
  bytes metered (429 `bytes_quota_exceeded`), strong ETag `"{id}@{corpusHash}"`
  with 304 counting nothing, `X-CommonRoot-Corpus` header, no attestation tag on
  whole editions (unverifiable under the verify caps; chapters and passages are
  the attested surface). Served live from BaseX through the same authentic-copy
  XQuery as the old route; publish-time precomputation (§6) deferred. The
  anonymous `/download/{key}` answers 410 with a pointer; the `/download` index
  stays as a plain-text pointer. About page: card buttons gone, download
  sentence rewritten in 13 bundles.
- **Not yet built:** plain-text display
  modes (501 today), comments
  and the About copy flip. Licensed editions are still served by the read
  routes; per the 2026-09-06 decision (the API is not a reading surface) they
  will 403 like downloads when phase 2 lands.

## 1. The surface at a glance

Everything lives under `/api/v1`. ⚖ Versioned from the first public call.

| Endpoint | Auth | Quota counter | Purpose |
|---|---|---|---|
| `GET /api/v1/health` | none | none | Uptime signal; the prerequisite (§9) |
| `GET /api/v1/texts` | key | requests | List editions with metadata |
| `GET /api/v1/texts/{src}` | key | requests | One edition's metadata |
| `GET /api/v1/texts/{src}/download` | key | **downloads** | Full edition, public domain only |
| `GET /api/v1/texts/{src}/{ref}` | key | requests | A chapter of one edition |
| `GET /api/v1/passages` | key | requests | Scattered verses/ranges across books, one edition |
| `GET /api/v1/comments` | key | requests | Comments by reference, no scripture — the embed payoff |
| `POST /api/v1/verify` | open? (§10) | per-IP | Check content claimed to be from here (§3.7) |

Eight routes. Anything not needed by the first consumer (§10.7) is left out on
purpose; a small v1 that never breaks beats a wide one that might.

`{src}` is the **source token from `link-format.md`** (`kjv`, `q-ar`, `buk-en`),
case-insensitive, with the document id (`bible-web`) also accepted the way
`/download` accepts both today. One naming scheme across reader links, About
cards and API — no second dialect.

`{ref}` is a **chapter reference in link-format notation** (`JHN.1`, `Q.2`,
`GEN.1`). The chapter is the retrieval unit for this route: it is what the
reader itself fetches (`forChapter`), what the cache layer can key cleanly
(§6), and what an embed needs. Scattered and verse-level retrieval is its own
route (§3.5) rather than an overload of this one, so the cacheable case stays
cacheable.

## 2. Authentication

⚖ API access needs an account; each account gets its own key; the key carries a
monthly allowance; the key raises your ceiling but never changes your
entitlement (the `@license` gate stays exactly where it is and learns nothing
about keys).

Mechanics, following the "cheap now, painful later" list:

- **Key format:** `crk_` + 40 base62 characters (`crk_` = Common Root key —
  greppable in a leaked repo, matchable by secret scanners). The first 8
  characters after the prefix are a **key id** stored in plaintext for display
  ("crk_a81f02xx…, created 2026-09-14, last used yesterday"); the whole key is
  stored only as a **SHA-256 hash** and shown once, at creation.
- **Several keys per account** (cap: 5), each with a free-text label, so
  rotation is create-new → move traffic → revoke-old, never downtime.
- **Header only:** `Authorization: Bearer crk_…`. A key in a query string is
  rejected with a 401 whose body says why (logs and Referer headers), not
  silently honoured.
- **Authorisation hangs off the existing ladder** (`access-control.md`): any
  Consumer-tier account may create keys. A key is a credential for the account,
  so a key's requests carry exactly the account's privileges — which for
  everything in this v1 means: Consumer suffices, and `comments=mine` sees that
  account's own drafts and nobody else's. No second permission system.
- **Key management is UI-first:** create/label/revoke on the account page.
  Management *via* the API (keys minting keys) is deliberately out of v1 — it
  is where key-handling bugs live, and no consumer needs it.
- **Revocation is immediate** (hash lookup per request; with ~tens of keys this
  is one indexed MySQL read, cacheable in-process for 60 s if it ever shows up
  in a profile).

Spring-side: one `OncePerRequestFilter` on `/api/**` resolving the key to the
account before the controller runs, so controllers see a normal authenticated
principal and the ladder applies as it does everywhere else.

## 3. Endpoints

### 3.1 `GET /api/v1/health`

No auth, no quota, no BaseX query in the hot path. Returns JSON:

```json
{
  "status": "ok",
  "corpusHash": "9f2c…",
  "subsystems": { "mysql": "ok", "basex": "ok", "meilisearch": "ok" },
  "time": "2026-09-02T06:00:00Z"
}
```

`status` is `ok` only when every subsystem is; otherwise `degraded` and HTTP
503, so a dumb uptime pinger (curl + cron, or an external monitor) catches the
next thirteen-day Meilisearch nap on day one. Subsystem checks are cached ~30 s
so the endpoint cannot itself become load.

### 3.2 `GET /api/v1/texts`

The `/download` index, grown up: one JSON array (or XML/plain per `?format=`),
**all** editions — not only downloadable ones — because a consumer resolving
tokens needs the full picture:

```json
{
  "corpusHash": "9f2c…",
  "texts": [
    {
      "id": "bible-web",
      "abbreviation": "WEB",
      "token": "web",
      "translation": "World English Bible",
      "language": "en",
      "direction": "ltr",
      "license": "Public domain",
      "downloadable": true,
      "bytes": 10485760
    }
  ]
}
```

`downloadable` is computed by the same `isPublicDomain` check that will refuse
the download — generated from the corpus, so the list can never advertise what
the gate then denies (the property the current `/download` index already has;
keep it).

### 3.3 `GET /api/v1/texts/{src}/download`

⚖ Download capability moves behind the API. This route replaces `/download/{key}`
(migration in §8), inheriting its behaviour verbatim:

- default-deny `@license` gate, prefix-match "public domain", unchanged;
- `global*Seq` attributes stripped — but at **publish time** now, not per
  request (§6), which also removes the per-request BaseX `copy` cost;
- `Content-Disposition: attachment`, filename `{id}.xml`.

**Format: XML only in v1.** The Decided per-call format switch stands for
passage calls; for full editions, XML is the honest artefact — it *is* the
corpus document against `religious-text.xsd`, precomputable and streamable.
A full-edition JSON or plain-text serialisation would be generated per request
(the exact load the design is trying to kill) and would create a second
canonical form of a 10 MB document. `?format=json` on this route returns 400
with a body pointing at the passage endpoint. If a real consumer needs bulk
JSON later, that is a v1.1 conversation with a precompute answer, not a 400→200
flip.

Counts against the **download counter** in bytes actually served (a 304 counts
zero — §6).

### 3.4 `GET /api/v1/texts/{src}/{ref}` — passages

The core read. Parameters:

| Param | Values | Default | Notes |
|---|---|---|---|
| `format` | `json` `xml` `text` | `json` | ⚖ per-call; `Accept:` honoured as fallback |
| `mode` | `verses` `original` `continuous` `chapters` `titles` | `verses` | `text` format only; the ReaderLink tokens, no new flags |
| `comments` | `public` `mine`, comma-combinable | *(omitted = none)* | ⚖ optional per call, off by default |

- **JSON** is an envelope: edition metadata (including `direction` — plain text
  cannot carry it, so JSON always states it), the reference as normalised
  link-format notation, verses as an ordered array, and — when requested — a
  `comments` sidecar (§3.5's shape, one object per comment, refs and all).
- **XML** is the chapter as a **valid subtree of `religious-text.xsd`** — the
  same bytes you would cut out of the bulk download, one schema, never an
  API dialect. With `comments` requested, XML returns **400** (see §10 on the
  namespaced-sibling question — this draft recommends comments stay JSON-only).
- **`text`** renders through the reader's display-mode pipeline, so scriptio
  continua (`mode=original`/`continuous`) survives into the API instead of
  dying in a join-with-spaces renderer. UTF-8, `charset` declared, direction
  bare (documented loudly). `text` + `comments` is **400**, not silent
  omission: a client that asked for comments and cannot get them should be
  told, not left debugging an absence.
- Edition-bound notes (LUT1912 translator footnotes) ride only when `{src}` is
  their own edition — `forChapter`'s `sourceId` passthrough, no guessing. ⚖

### 3.5 `GET /api/v1/passages` — scattered references

The use case that shaped it (maintainer, review of this draft): *sometimes you
just want a couple of verses scattered from throughout the Bible.* One
edition, many places:

```
GET /api/v1/passages?src=kjv&refs=JHN.1.1,ROM.9.5,PSA.23.1-6
```

| Param | Values | Notes |
|---|---|---|
| `src` | source token | required; one edition per call |
| `refs` | comma-separated link-format refs | required; verses, ranges, chapters, books — whatever `RefListParser` accepts ⚖ (self-describing refs) |
| `format` | `json` `xml` `text` | as §3.4 |
| `mode` | display-mode tokens | as §3.4, `text` only |
| `comments` | `public` `mine` | as §3.4 |

- **Cap: 20 refs per call** — the `hl` param's max-12 precedent, loosened a
  little because this route *is* the point rather than a decoration. Over the
  cap: 400, naming the cap.
- Response groups verses **by ref, in the order asked**, each group labelled
  with its normalised ref, so `refs=ROM.9.5,JHN.1.1` round-trips in that order.
- These are chapter-window-sized BaseX reads — the reader's own query shape —
  under the request counter, which is exactly what that counter is for.
- Caching is honestly worse here: arbitrary ref combinations don't share cache
  keys, so responses get the corpus-hash ETag (scripture-only calls are still
  immutable between pushes ⚖) but only `max-age=300`, and no expectation that
  Caddy absorbs much. The load answer for this route is the quota, not the
  cache.
- `{src}/{ref}` (§3.4) stays the right door for "give me John 3" — one ref,
  clean cache key. This route is for the scatter.

### 3.6 `GET /api/v1/comments` — the payoff route

Comments with no scripture attached: what a channel embed wants, and nearly
free once comments are a sidecar. ⚖ Comments live outside the scripture
structure and refer to verses by reference.

| Param | Values | Notes |
|---|---|---|
| `ref` | link-format reference(s), comma-separated | required; `JHN.1.1`, `Q.2.255` — self-describing, `RefListParser`-compatible ⚖ |
| `src` | source token | optional; enables edition-bound notes for that edition only |
| `set` | `public` (default) `mine` | `mine` = own comments incl. drafts, response goes private (§6) |

Semantics carried from the design notes, all ⚖ or argued there:

- `public` = public-and-approved via the reader's own query; never anyone
  else's drafts/pending/rejected; own comments carry their state.
- **One comment, one appearance**, with its full reference list, including refs
  outside the asked-for passage — the cross-reference signal is the point.
- **Attribution always**: channel name + video link on every comment.
- No mute-list filtering — data interface, not personalised view.
- `publicId` (`cmt_…`) is the API identity *and* the permalink; a comment in a
  payload is one string away from a URL a human can open.
- Pagination: the reader's "first N" cap, same N, with a plain
  `truncated: true` marker. No cursor machinery in v1.

Response shape (JSON only, per the §10 recommendation):

```json
{
  "ref": ["JHN.1.1"],
  "comments": [
    {
      "id": "cmt_8kq2…",
      "url": "https://common-root.org/reader?comment=cmt_8kq2…",
      "channel": "Theological Apologia",
      "video": "https://youtube.com/watch?v=…",
      "refs": ["JHN.1.1", "ROM.9.5"],
      "state": "published",
      "body": "…"
    }
  ],
  "truncated": false
}
```

### 3.7 Attestation and `POST /api/v1/verify`

The maintainer's proposal, adopted: responses carry a tag computed from **the texts
plus a secret held server-side**, and a verify method accepts content claimed
to be from here and says whether it is.

**What it attests — be precise, because the claim is the product.** "Does this
quote match the edition?" needs no secret: the corpus is the ground truth and
a comparison answers it. The secret tag attests two things comparison cannot:
**provenance** ("this bundle was served by common-root.org, unmodified") and —
the quiet win — **past versions**: the corpus changes with every weekly
publish, and a tag over content + corpus hash verifies a download from *any*
prior publish by recomputation alone, with no archived corpora. For a project
whose whole premise is "check what the text actually says," a verifiable quote
is on-mission: an embed can carry a "verified" badge that resolves through
this endpoint.

**The tag:**

- `HMAC-SHA256(secret, canonical-form)`, where the **canonical form is
  defined, versioned, and documented** — edition id, corpus hash, normalised
  refs, verse texts, fixed separators, UTF-8, NFC. **Never the wire bytes**: a
  consumer who re-serialises the JSON, converts format, or gains a trailing
  newline must not break verification through no fault of their own. The docs
  page publishes the canonical-form recipe so a client can assemble it.
- Wire shape: `attestation: { "alg": "HMAC-SHA256", "kid": "k1",
  "corpusHash": "9f2c…", "canon": "v1", "tag": "…" }` in every JSON envelope
  (passages, scattered passages); on XML downloads, the same fields as a
  documented processing-instruction/comment block before the root element, so
  the document itself stays schema-pure.
- **`kid` is the rotation story**: secrets rotate; retired secrets are kept
  verify-only, keyed by `kid`, so a tag minted in March still verifies in
  November. Losing a retired secret silently invalidates every tag it minted —
  they live in the same backup regime as the database.
- A leaked secret makes forgeries verifiable — the tag is only as good as the
  secret's handling. Server-side env/file, never in the repo, rotated on any
  suspicion (rotation is cheap *because* of `kid`).

**Plain text carries the tag too** (maintainer, review): because the tag is over
the canonical form, attaching it to a text response cannot invalidate it. Two
carriers: every `format=text` response gets an
`X-CommonRoot-Attestation: kid=…; canon=…; corpus=…; tag=…` header; and
`?attest=1` opts into a delimited trailing block in the body carrying every
field verify needs — headers vanish when a response is saved or pasted, and
plain text is exactly the format that gets saved and pasted, so the block
makes a saved `.txt` a self-contained verifiable object. The block is excluded
from the canonical form by construction (only verse texts go in). Opt-in, not
default: `mode=original` piped into a renderer should not sprout a footer.
Docs state the honest limit: the block is data, not protection — anyone can
strip or alter it; its value is that *if* it verifies, the text is ours,
unmodified, for that corpus version. A missing block proves nothing either way.

**The verify method:**

```
POST /api/v1/verify
{ "src": "kjv", "corpusHash": "9f2c…", "canon": "v1", "kid": "k1",
  "refs": ["JHN.1.1"], "texts": ["In the beginning was the Word…"],
  "tag": "…" }
```

Response: `{ "valid": true, "attests": "served by common-root.org for corpus
9f2c…" }` — or `valid: false` with *no hint of why* (a why is an oracle for
forging). When `corpusHash` is current, the response may add
`matchesCurrent: true/false` as a courtesy comparison against the live corpus.
Cost: one HMAC, no corpus read for the past-version case. Body capped (64 KB,
say) — enough for any honest quote bundle, no use as a free hashing service.

**Auth tension, flagged not decided (§10):** the natural verifier is a skeptic
*without* an account — someone checking a quote in a video. That argues for
verify being **keyless with a per-IP cap**, but "API access needs an account"
is Decided ⚖, so the exception is the maintainer's to grant.

**Later, not now:** if embeds want client-side verification without calling
home, the upgrade path is an **Ed25519 signature with a published public
key** — same canonical form, verifiable offline and while the box is down.
One sentence in the docs reserving the possibility; nothing built.

A verify endpoint is a promise that someone's "verified" badge keeps working —
it sits behind the same health/uptime gate as everything else (§9).

## 4. Formats, summarised

| | JSON | XML | text |
|---|---|---|---|
| Passages | ✔ default | ✔ `religious-text.xsd` subtree | ✔ via display modes |
| Full download | — (400) | ✔ only form | — (400) |
| Comments | ✔ only form | 400 | 400 |
| Direction | in metadata | in the document | absent — documented |

`?format=` wins over `Accept:`; both beat nothing; UTF-8 everywhere. ⚖/prop.

## 5. Quotas and rate limits

⚖ Two counters, never one clever unit; the allowance must comfortably exceed
the honest maximal use (whole-corpus researcher) so abuse looks like 100×,
not 2×.

**Numbers — placeholders until the maintainer ratifies (§10):**

| Counter | Default allowance | Sized against |
|---|---|---|
| Downloads | **5 GB / month** | full PD corpus ≈ 63 editions × ~10 MB ≈ 650 MB → allowance ≈ 8 whole corpora |
| Requests | **20 000 / month** | reading every chapter of every PD edition ≈ 75 k? No: honest per-app use is hundreds/day; 20 k ≈ 650/day sustained |
| Burst | **60 requests / minute / key** | protects the box from loops regardless of monthly headroom |

- **Calendar month, UTC** (recommendation, §10): resets on the 1st. A rolling
  window is fairer at the margin but is a query over a request log; a calendar
  month is one counter row per key per month (`key_id`, `yyyymm`, `requests`,
  `bytes`) that a developer can also hold in their head ("resets on the 1st").
- Every response carries `X-RateLimit-Limit`, `-Remaining`, `-Reset` for the
  counter it consumed (download responses report the byte counter). Over the
  line: **429 + `Retry-After`** ⚖ — the reset moment for monthly counters, next
  minute for burst.
- The burst limiter is in-app (a per-key token bucket in memory — one box, no
  distributed state needed). Additionally a **per-IP ceiling in Caddy** on
  `/api/*` (say 120/min/IP) so unauthenticated junk — bad keys, scanners, the
  crawler swarm probing — is shed before Spring wakes up. That per-IP limit is
  infrastructure hygiene, not an anonymous tier, and implies nothing for the
  open-vs-keyed question.

## 6. Caching — what makes this affordable

⚖-adjacent: this section is the load-control motivation made concrete.

- **Publish-time precompute.** The weekly publish already change-gates on a
  corpus hash. At that moment, for each public-domain edition, write the
  stripped (`global*Seq`-free) XML to disk. `…/download` then **streams a file
  and increments a counter** — I/O and one UPDATE, BaseX untouched. This
  resolves the noted Caddy-vs-quota tension the only way that keeps score:
  the request reaches the app, but the app does almost nothing.
- **ETag = corpus hash** (per-edition: hash + edition id) on downloads and on
  scripture-only passage responses. Scripture is immutable between pushes ⚖,
  so repeat pulls are 304s that cost a header comparison and count zero bytes.
- Passage responses *without* comments: `Cache-Control: public, max-age=3600`
  + the strong ETag. *With* `comments=public`: no ETag from the corpus hash,
  `max-age=60` — comments move between pushes ⚖. *With* `mine` anywhere:
  **`Cache-Control: private, no-store`** — drafts must never touch a shared
  cache ⚖.
- Passage reads do hit BaseX (unlike downloads), which is fine: they are the
  reader's own chapter-sized queries, and the 3600 s public cache means Caddy
  can absorb repeats if a passage ever gets hot.

## 7. Errors

One JSON error shape everywhere (even when `format=text` was requested — an
error is not a passage):

```json
{ "status": 429, "error": "quota_exceeded",
  "message": "Monthly request allowance used. Resets 2026-10-01T00:00:00Z.",
  "docs": "https://common-root.org/api/docs#quotas" }
```

| Status | When |
|---|---|
| 400 | bad ref/token/format, refused combination (`text`+comments, XML comments, JSON download) |
| 401 | missing/revoked/malformed key; key in query string (with the why) |
| 403 | licence gate ⚖ — same wording spirit as today's `/download` refusal |
| 404 | unknown source/reference — with a pointer to `/api/v1/texts` |
| 429 | quota/burst — always with `Retry-After` ⚖ |
| 503 | `health` degraded; also honest answer during publish swap |

## 8. Migration: `/download`, and the About promise

⚖ The copy change travels with the feature; `about.sources.download` becomes
false in all 13 bundles the day a key is required.

Sequence, assuming key-required is confirmed:

1. Ship `/api/v1` complete with self-serve instant key creation — the
   replacement promise ("a free account, a key in one click, and the whole
   corpus is yours") must be true *before* the old one is retired.
2. Flip the 13 bundles' download sentence in the same deploy.
3. `/download` (index) keeps returning 200: a plain-text pointer at
   `/api/v1/texts` and the docs page — it is likely bookmarked and linked.
   `/download/{key}` returns **410 Gone** with the same pointer. No silent
   redirect that would quietly re-open an unmetered path, and no long
   double-serving window during which the About page lies one way or the
   other.
4. A human docs page at `/api/docs` (one static page: auth, quotas, the six
   routes, a `curl` example that works when pasted) ships with step 1 —
   clickable `?format=` examples are the reason `?format=` exists.

## 9. Prerequisites, restated as a gate

⚖ Before the first external consumer: `health` endpoint (§3.1) + an external
uptime check pointed at it + the burst limiter (§5). These are inside the v1
scope on purpose — phase 0 below, not a follow-up. An API is a promise that
someone else's site keeps working.

## 10. Recommendations on the open questions

Each is a recommendation with its reason; none is settled until the maintainer says so.

1. **Quota numbers:** 5 GB + 20 000 requests + 60/min burst (§5). Sized so the
   honest whole-corpus researcher never notices the meter exists.
2. **Calendar month vs rolling 30 days:** calendar month, UTC. One counter row,
   explainable in five words, and the failure mode (a heavy user waits until
   the 1st) is benign at these allowances.
3. **Key-required vs open-but-metered downloads:** the dissent stays recorded
   in `api-design.md` and is not re-argued here; this spec is written
   key-required as the maintainer is leaning. One observation for the decision, not an
   argument: §8's sequencing means the open-door About sentence is replaced,
   never briefly false — which answers the strongest practical objection.
4. **Comments in XML:** **JSON-only.** The one-schema promise ("a chapter from
   the API and from the bulk download are the same bytes") is worth more than
   XML feature-parity, and a namespaced sibling would still be a wire format
   someone parses and we then can't change. Nothing about JSON-only forecloses
   a later `<cr:comments>` sibling; shipping one *would* foreclose changing it.
5. **Plain text + comments:** refuse, 400 (§3.4). Silent omission is a
   debugging session shipped to someone else.
6. **Per-IP limiting for an anonymous tier:** no anonymous tier exists under
   key-required, but a per-IP ceiling in Caddy ships anyway as infrastructure
   (§5) — it is what actually meets the crawler swarm at the door.
7. **First consumer:** design and document against **channel embeds** —
   `GET /api/v1/comments?ref=…` is the route the docs page leads with, and the
   outreach thread gets "here is a snippet that puts your argument, credited
   and linked, on your page" as its opening line. "Developers in general" are
   served by the same routes without being designed for.
8. **Is `verify` keyless?** Recommend **yes** — keyless, per-IP capped, body
   capped. The verifier the feature exists for has no account and should not
   need one to distrust a quote. This is a one-route exception to the Decided
   key requirement, so it is explicitly the maintainer's call (§3.7).
9. **Scattered-refs cap:** 20 per call (`hl`'s 12, loosened). A number to
   ratify, not a principle.
10. **HMAC now, signature later:** HMAC-SHA256 with `kid` rotation for v1;
    Ed25519 offline verification only if an embed consumer actually asks
    (§3.7). The canonical form is the part to get right first — it outlives
    both algorithms.

## 11. Build order (each step shippable alone)

| Phase | Contents | Note |
|---|---|---|
| 0 | `health` + external uptime check + Caddy per-IP ceiling | valuable even if the API stalls here |
| 1 | key model (hash, prefix, multi-key, UI) + auth filter + counters + burst limiter | no new data served yet; testable with a stub route |
| 2 | `texts` list/detail + publish-time precompute + `download` behind the key + §8 migration + docs page + 13-bundle copy flip | the Decided core, delivered |
| 3 | passages: chapter route + scattered `refs` route (`format`/`mode`/`comments`) | reuses reader queries |
| 4 | attestation tags + `POST /verify` | canonical form specified in phase 3's docs, tags added to phase 2's downloads retroactively |
| 5 | `comments` by ref + embed snippet for outreach | the payoff, aimed at the first consumer; "verified" badge uses phase 4 |

Phases 0–2 are the smallest thing that honours every Decided item. 3–5 can
trail by weeks without anything on the About page being untrue.

---

*Not covered here, deliberately: write access of any kind, key management via
API, search over the API, webhooks. Each is a scope door best opened by a
consumer who exists. (Verse-range retrieval was on this list; the maintainer's review
pulled it into scope as §3.5.)*
