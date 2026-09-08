# API v1 — test-case catalogue

Companion to `api-spec.md`. Written 2026-09-02, before the code, so the tests
are the spec restated as obligations rather than a description of whatever got
built. Grouped by phase; each case names the behaviour and the exact expected
outcome. Unit cases become JUnit; the `curl`-level cases become integration
tests against the running app (local BaseX + MySQL, see README.md).

## Schema under test (phase 1)

```sql
-- As built in V18__api_keys.sql (typed VARCHAR(40) ids like the rest of the schema;
-- VARCHAR not CHAR throughout, or Hibernate's validate step refuses the columns).
CREATE TABLE api_keys (
  id           VARCHAR(40) PRIMARY KEY,           -- key-{uuidv7}
  user_id      VARCHAR(40) NOT NULL,              -- FK -> users
  key_id       VARCHAR(8)  NOT NULL UNIQUE,       -- plaintext display prefix
  key_hash     VARCHAR(64) NOT NULL UNIQUE,       -- SHA-256 hex of full key
  label        VARCHAR(64),
  created_at   DATETIME NOT NULL,
  last_used_at DATETIME NULL,
  revoked_at   DATETIME NULL
);

CREATE TABLE api_usage (
  api_key_id VARCHAR(40) NOT NULL,                -- FK -> api_keys
  period     VARCHAR(6)  NOT NULL,                -- yyyymm, UTC
  requests   BIGINT  NOT NULL DEFAULT 0,
  bytes      BIGINT  NOT NULL DEFAULT 0,
  PRIMARY KEY (api_key_id, period)
);
```

`users` is deliberately untouched: several keys per account, rotation without
downtime.

---

## Phase 0 — health

| # | Case | Expect |
|---|---|---|
| H1 | All subsystems reachable | 200, `status:"ok"`, all subsystems `"ok"`, `corpusHash` present |
| H2 | Meilisearch down (the thirteen-day case) | **503**, `status:"degraded"`, `meilisearch:"down"`, others `"ok"` |
| H3 | BaseX down | 503, `basex:"down"` |
| H4 | Subsystem checks cached | two calls < 30 s apart → one probe per subsystem (verify via mock invocation count) |
| H5 | No auth required | request with no key → 200/503, never 401 |
| H6 | Health never counts | no `api_usage` row created or incremented |

## Phase 1 — keys

| # | Case | Expect |
|---|---|---|
| K1 | Key generation format | matches `^crk_[0-9A-Za-z]{40}$`; first 8 after prefix = stored `key_id` |
| K2 | Plaintext shown once | creation response contains full key; no later read path returns it; DB holds only hash |
| K3 | Hash at rest | stored value = SHA-256 hex of the full key string; unit-test with a fixed vector |
| K4 | Valid key resolves | `Authorization: Bearer crk_…` → request runs as the owning account |
| K5 | Unknown key | 401, JSON error shape, `error:"invalid_key"` |
| K6 | Revoked key | revoke, then use → 401 immediately (within the 60 s cache tolerance if caching is on — pin the tolerance in the test) |
| K7 | Key in query string | `?key=crk_…` and no header → **401 with a body explaining query strings are rejected**, even if the key is valid |
| K8 | Malformed header | `Bearer` missing, wrong scheme, empty → 401, never 500 |
| K9 | Multiple keys per account | two live keys both work; revoking one leaves the other working (the rotation story) |
| K10 | Key cap | 6th key creation refused with a clear error (cap = 5) |
| K11 | `last_used_at` advances | successful call updates it; failed auth does not |
| K12 | Key never raises entitlement | licensed edition (NIV) download with a valid key → **403 from the licence gate, unchanged wording spirit**; the gate code has no key-awareness to test *for* — assert the refusal is identical with and without a key |

## Phase 1 — quotas and burst

| # | Case | Expect |
|---|---|---|
| Q1 | Request counting | N passage calls → `api_usage.requests` = N for (key, current yyyymm) |
| Q2 | Byte counting | download of a known-size file → `bytes` grows by exactly Content-Length; `requests` unchanged (two counters, never one) |
| Q3 | 304 counts zero | conditional download with matching ETag → 304, `bytes` unchanged |
| Q4 | Headers on every response | `X-RateLimit-Limit/-Remaining/-Reset` present, arithmetic consistent (`Remaining` = `Limit` − used) |
| Q5 | Download reports byte counter | download response's rate-limit headers describe bytes, not requests |
| Q6 | Over monthly quota | 429, `Retry-After` = seconds to the 1st of next month UTC, JSON error `quota_exceeded` with reset timestamp |
| Q7 | Month boundary | with clock at 23:59:59 UTC on the last day vs 00:00:01 on the 1st → separate `period` rows; allowance is fresh (inject the clock; never sleep) |
| Q8 | Burst limit | 61 calls inside one minute with monthly headroom → 61st is 429, `Retry-After` ≤ 60 |
| Q9 | Burst is per key | key A exhausting burst does not 429 key B |
| Q10 | 401s don't count | failed-auth requests create no usage rows (quota is for users, not attackers — attackers are Caddy's per-IP job) |

## Phase 2 — downloads behind the key

| # | Case | Expect |
|---|---|---|
| D1 | PD edition with key | 200, XML attachment, filename `{id}.xml` |
| D2 | Sequence attributes stripped | response contains no `globalCanonicalSeq` / `globalChronologicalSeq` / `globalTanakhSeq` — *as attributes* (existing controller's node-vs-text guarantee carried into precompute: a verse whose text contains the literal string still passes) |
| D3 | Schema validity | downloaded document validates against `religious-text.xsd` |
| D4 | Same bytes as precompute | response bytes identical to the publish-time file on disk (streaming, not regeneration) |
| D5 | ETag = corpus hash | repeat with `If-None-Match` → 304 |
| D6 | Licence gate: CC edition | Hindi IRV / Turkish YTC → 403 (redistributable ≠ public domain; decisions, not defaults) |
| D7 | Licence gate: qualified PD | Yusuf Ali ("Public domain in EU… US URAA to 2033") → 403 (the existing `isPublicDomain` unit tests carry over verbatim) |
| D8 | `format=json` on download | 400 pointing at the passage endpoint |
| D9 | Abbreviation or id | `web` and `bible-web` fetch the same document |
| D10 | Old routes | `/download` → 200 pointer text; `/download/{key}` → **410** with pointer (after migration flag is on) |

## Phase 3 — passages

| # | Case | Expect |
|---|---|---|
| P1 | Chapter JSON | `GET …/kjv/JHN.1` → envelope with edition metadata incl. `direction`, normalised ref, ordered verses |
| P2 | Chapter XML | valid `religious-text.xsd` subtree; byte-compare a verse against the same verse cut from the D4 download |
| P3 | Plain text modes | each of `verses/original/continuous/chapters/titles` → matches the reader's rendering of the same chapter (golden files); `original` preserves scriptio continua (no injected spaces) |
| P4 | RTL in text | `q-ar` chapter as text → bare RTL text, no direction marks injected; JSON metadata says `"direction":"rtl"` |
| P5 | `text` + `comments` | 400, explicit refusal (never silent omission) |
| P6 | XML + `comments` | 400 (JSON-only comments) |
| P7 | Edition-bound notes | LUT1912 request carries 1912 footnotes; KJV request carries none; unnamed edition → omitted, not guessed |
| P8 | Scattered refs happy path | `refs=JHN.1.1,ROM.9.5,PSA.23.1-6` → three groups, asked order, normalised labels |
| P9 | Refs cap | 21 refs → 400 naming the cap (20) |
| P10 | Bad ref | `refs=NOPE.1.1` → 400 naming the offender, good refs not partially served |
| P11 | Unknown source token | 404 with pointer to `/api/v1/texts` (note: **deviation from the reader**, which falls back to the default Bible — an API must not guess) |
| P12 | Cache headers | scripture-only: strong ETag + `public`; `comments=public`: `max-age=60`, no corpus ETag; any `mine`: `private, no-store` |

## Phase 4 — attestation & verify

| # | Case | Expect |
|---|---|---|
| A1 | Canonical form vectors | fixed inputs (edition, hash, refs, texts incl. an RTL verse and a combining-mark case) → byte-exact canonical string (golden vectors, NFC asserted) |
| A2 | Tag correctness | HMAC-SHA256 over A1 vectors with a test secret → expected tag (independent recomputation in the test, not the production code path) |
| A3 | Wire independence | same passage as JSON and as `text&attest=1` → identical `tag` (canon ≠ wire bytes) |
| A4 | Text footer | `attest=1` → delimited block with all verify fields; without the param → no block; block absent from canonical form (A3 already proves it) |
| A5 | Attestation headers | every `format=text` response carries `X-CommonRoot-Attestation` with kid/canon/corpus/tag |
| A6 | Verify: valid | POST round-trip of a served bundle → `valid:true`, attests string names the corpus hash |
| A7 | Verify: tampered text | one character changed → `valid:false`, **no reason given** (no oracle) |
| A8 | Verify: tampered refs / edition / hash | each field mutated singly → `valid:false`, same opaque response as A7 (responses byte-identical across failure causes) |
| A9 | Verify: past version | tag minted under old corpus hash, corpus since republished → `valid:true` with no corpus read (mock BaseX and assert zero calls) |
| A10 | Key rotation | tag minted with retired `kid` → still `valid:true`; unknown `kid` → `valid:false`, opaque |
| A11 | Body cap | > 64 KB body → 413 |
| A12 | Verify timing | constant-time tag comparison (`MessageDigest.isEqual`, asserted by code inspection/architecture test, not a timing measurement) |

## Cross-cutting

| # | Case | Expect |
|---|---|---|
| X1 | Error shape everywhere | every non-2xx (incl. on `format=text` requests) is the JSON error object with `status/error/message/docs` |
| X2 | UTF-8 | every response declares charset; a verse with combining marks survives a full round trip byte-identically |
| X3 | BaseX writes | architecture test: nothing under the api package issues a BaseX write (read-only API; and the RestTemplate-corrupts-prolog rule never comes into play) |
| X4 | `/api/**` filter scope | reader routes (`/reader`, `/download` pointer) unaffected by the auth filter |

---

Deliberately absent: load tests (one box; the quota *is* the load answer) and
Caddy per-IP tests (infrastructure, verified by hand once against the prod
config, documented in the deploy checklist instead).
