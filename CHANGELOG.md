# Changelog

All notable changes to **Common Root?** will be documented here. The format is
loosely based on [Keep a Changelog](https://keepachangelog.com/) and the project
follows [Semantic Versioning](https://semver.org/) (with the pre-1.0 conventions
described below).

## Versioning convention

- **`0.0.x` — pre-alpha.** Rapid iteration; PATCH bumps for every feature or
  bug fix. Features and fixes share the same digit because the product is
  changing under the hood every commit.
- **`0.x.y` — alpha / beta.** Once the reader is feature-complete enough to
  share, `0.1.0` is the first beta. MINOR (`y` in `0.x.y`) ⇒ feature.
  PATCH (`x` in `0.x.y` after the dot we don't have yet) ⇒ bug fix on top of
  the current beta.
- **`1.0.0` — first stable.** From there, standard SemVer: MAJOR (breaking),
  MINOR (feature), PATCH (fix).

### SNAPSHOT semantics

Between releases the pom version carries a `-SNAPSHOT` suffix (the Maven
convention for "in-development, not-yet-released"). The version badge in the
reader toolbar reads e.g. `v0.0.2-SNAPSHOT` so you can always tell at a glance
whether a running build is a tagged release or an in-progress build.

The per-release ceremony is small:

1. While developing: pom version stays at e.g. `0.0.2-SNAPSHOT`. Commits accrue
   under the `[Unreleased]` section at the top of this file.
2. To cut the release: bump the three poms to plain `0.0.2`, move the
   `[Unreleased]` entries under a new `[0.0.2] — YYYY-MM-DD` heading, **run
   `python3 docs/build_docs.py`**, commit ("Release 0.0.2"), tag `v0.0.2`, push
   both branch and tag.
   Then, still at the release commit, **`scripts/publish/publish_public.sh
   publish`** — it exports the allow-listed paths (`scripts/publish/public-paths.txt`)
   into the checkout of the public mirror (`../common-root`), sweeps the export
   for anything that must not leave, and makes the one public commit per release,
   "Release 0.0.2", tagged the same. It refuses on a snapshot version, a dirty
   tree, a missing tag or a sweep finding.
3. Immediately after: bump the three poms to `0.0.3-SNAPSHOT`, commit
   ("Bump to 0.0.3-SNAPSHOT"), push. A fresh empty `[Unreleased]` section goes
   at the top of this file.

**The documentation is rebuilt in every release.** `docs/build_docs.py` renders
the overview from the same prose the edition pages carry, so a release that skips
it ships a site saying one thing and a document saying another — and the
documents are what people forward to someone who will never open the site. It is
part of step 2, before the release commit, so the regenerated files land in that
commit rather than trailing after it. `git status --short docs/` should be clean
once it has run and been staged; anything still dirty means the build produced
changes nobody committed.

**A SNAPSHOT is never promoted to production.** Prod pulls `app:latest`, so
publishing a snapshot image under that tag deploys it whether or not anyone
meant to, and the badge in the toolbar then names a version that cannot be
checked out. Prod images are built from the release TAG, not from `main`:
`gh workflow run "Build and publish app image" --ref v0.6.8`. CI enforces this —
the build fails on a `-SNAPSHOT` version unless `allow_snapshot` is set, and a
snapshot built that way is never tagged `:latest` and never deployed. If a fix
needs to ship, it needs a release; that is the whole cost of the rule and it is
worth paying.

All three poms (`pom.xml`, `app/pom.xml`, `ingestion/pom.xml`) move in lockstep
— the two child poms reference the parent via `<parent><version>...`.

Each release gets a Git tag (`git tag v0.0.1 -m "..."`). The version is
filtered from `pom.xml` into `application.properties` at build time and shown
in the reader toolbar and About-page nav.

---

## [0.8.5] — 2026-09-08

### Added
- **Downloads through the API** (#1): `GET /api/v1/texts/{token}/download`
  serves a whole edition as the authentic corpus XML — the same document the
  old route served — behind the key, metered against the key's monthly byte
  allowance (429 `bytes_quota_exceeded`), licence-gated like every text route,
  with a strong ETag `"{id}@{corpusHash}"` so a repeat pull with
  `If-None-Match` is a 304 that counts nothing. XML only; whole editions carry
  no attestation tag (a 31,000-verse bundle cannot be verified under the
  `/verify` caps — chapters and passages remain the attested surface).
  Documented on `/api/docs#downloads`; five Postman requests (D1–D5).

### Removed
- **The anonymous download** (#2): `/download/{key}` answers 410 Gone with a
  pointer to the API; the `/download` index stays as a plain-text pointer
  because it is bookmarked. The ⤓ XML buttons are gone from the About-page
  cards and the "Take the texts with you" paragraph now describes the keyed
  route, in all 13 bundles in the same release. Reading the site still needs
  no account; only taking the corpus away does.

### Changed
- `docs/api-spec.md` §0 records the download as built; deliberation phrasing
  in the public documents replaced by "the maintainer", and the export sweep
  now flags it.

Closes #1, closes #2.

## [0.8.4] — 2026-09-08

### Added
- **Current location.** A signed-in reader's position is now recorded from
  the first chapter they read — no visit to Preferences needed — and the reader
  opens there on the next bare `/reader`. Preferences shows it under "Current
  location", one line per column with localised book names ("KJV · Genesis
  12:3", "Q-AR · Surah 2:255"), an *Open* link that replays it and a *Forget*
  button for starting a new read-through. Unticking "Continue where I left off"
  still stops the recording and forgets the position. Preferences also gained a
  *Profile* button — there was no way back.
- `CONTRIBUTING.md` in the public mirror: PRs are applied to the working
  repository and appear in the next release commit; issues are the backlog.
- Public issue tracker at github.com/christa-claw/common-root/issues, seeded
  with the open items from the engineering notes and design documents, plus
  one "Verify … texts" issue per interface language.

### Changed
- `publish_public.sh` compiles the export (`mvn -o compile`, both modules)
  before pushing and refuses a tree that would not build from a clean clone.
- Public repository settings: Actions, Wiki and Projects off (nothing is built
  there), Dependabot alerts on, head branches deleted on merge, and a ruleset
  that blocks deleting or moving `v*` tags. Dependabot alerts on for the
  working repository too.

## [0.8.3] — 2026-09-08

### Changed
- **Edition information pages dual-licensed** AGPL-3.0-or-later / CC BY-SA 4.0
  (`i18n/editions/LICENSE.md`, `NOTICE`), so the prose can be reused the way
  encyclopaedic text is, with attribution and share-alike.

## [0.8.2] — 2026-09-08

### Changed
- **Licence: GNU Affero General Public License v3.0 or later.** The public
  mirror's first export (0.8.1, 2026-09-08) went out under Apache-2.0 and was
  relicensed the same morning; that one release stays available under the
  terms it carried, everything from 0.8.2 is AGPL-3.0-or-later. Every Java,
  Python and shell source now opens with an SPDX line and copyright notice;
  `pom.xml` declares the licence; `NOTICE` spells out the section-13
  obligation for anyone running a modified instance as a service.

## [0.8.1] — 2026-09-08

### Added
- **Public mirror of the code** at github.com/christa-claw/common-root, under
  Apache-2.0. `scripts/publish/publish_public.sh` exports an allow-list of
  tracked paths (`scripts/publish/public-paths.txt`) as one commit per release
  — the public history is the sequence of releases, nothing else. Design
  notes, correspondence, audits, the argument and note ledgers, the channel
  registry and the automation plists stay private; the Docker build gets
  empty placeholders for the data files it expects. `check` mode runs a leak
  sweep (gitleaks when installed, plus pattern greps and a dangling-reference
  list) and is what `publish` refuses on.

- The Postman collection (`docs/api-postman-collection.json`) is served at
  `/docs/api-postman-collection.json` and linked from `/api/docs`; its default
  `baseUrl` is production. The stale September-4 duplicate is gone.

### Changed
- Reader Preferences shows the unabbreviated reading-order names
  (`order.full.*` in all 13 bundles); the toolbar keeps the short forms.
- Machine-specific paths removed from code and scripts: the local bible-api
  clone is `$BIBLE_API_SOURCE` (default `~/bible-api-source`) for the
  ingestion service and `03_ingest_originals.py`; `02_extract_ctl.sh` and
  `make_og_image.py` find the repo relative to themselves. Point
  `BIBLE_API_SOURCE` at the clone before running either.
- Code comments no longer cite the private engineering notes by file name.

## [0.8.0] — 2026-09-07

### Added
- **Web API v1** (`/api/v1`, docs at `/api/docs`; design in `docs/api-design.md`,
  spec in `docs/api-spec.md`, test obligations in `docs/api-test-cases.md`).
  Read-only and key-authenticated — reading the site itself still needs no
  account; the API is not a reading surface.
  - **Keys** (`crk_…`, hashed at rest, shown once, up to five live per account,
    header-only — a key in a query string is refused even when valid) with two
    monthly allowances per key (requests and download bytes, calendar month
    UTC) plus a per-key burst limit; `X-RateLimit-*` on every metered response,
    `429 + Retry-After` past the line. Managed from the profile page.
  - **Editions** (`/texts`, `/texts/{token}`, `/texts/{token}/books` — the
    table of contents with the chapters that actually exist), **passages**
    (`/texts/{token}/{ref}` as JSON or corpus XML; `/passages?refs=` for
    scattered verses across books and editions, `kjv:1JN.5.7,web:1JN.5.7`),
    all addressed by the reader-link vocabulary (tokens, `JHN.3.16`, `Q.2.255`).
    JSON verse text is the reader's display text; XML is the raw corpus element.
  - **Attestation**: every passage response carries an HMAC over a canonical
    form of the texts (edition, ref, cleaned verses, corpus label, mint time,
    build) — never the wire bytes — so a saved response verifies later via
    `POST /verify`, which answers `valid` plus `corpus: current | superseded`
    and never says why a bundle failed. XML responses embed it as a processing
    instruction; a saved file is self-validating.
  - **Licence gate**: licensed editions (NIV, NASB, …) are 403 on every API text
    route whatever key is presented — the About page's "not redistributed
    outside the platform" made true for the API too. Metadata stays open.
  - `GET /api/v1/health` (no key): 200/503 with a per-subsystem breakdown — the
    uptime signal the thirteen-day Meilisearch nap showed we lacked.
- V18 migration: `api_keys`, `api_usage`.
- **Edition info pages for three more editions, each in all 13 interface
  languages:** the Berean Standard Bible (BSB), Darby 1885 (DBY) and
  Diodati 1649 (DIO). Agricola 1548 (AGR1548) revised in all 13 languages.

### Changed
- `nightly_content.sh` runs `03_enrich_arguments.py` after extraction, so new
  arguments carry per-verse timecodes (`&t=`) — the "Watch the argument" link
  had been landing at the top of the video for everything extracted since July.
- `transcripts/arguments.json` re-enriched: 21,354 of 21,364 refs now carry a
  video link, 7,965 a timecode (was 3,449 / 1,112).

### Not yet
- Downloads through the API (`/texts/{token}/download`) and the retirement of
  the anonymous `/download` route with its About-page sentence: next release.
  `/download` is unchanged in this one.

## [0.7.5] — 2026-09-01

### Added
- **Edition info pages for seven more editions, each in all 13 interface
  languages:** the Geneva Bible (GNV), Douay-Rheims (DRA), American Standard
  Version (ASV), the 1938 Korean Revised Version (KR3338), Elberfelder (ELB),
  the Chinese Union Version (CUV) and the Modern Hebrew Bible (HEBM).
- **Two new channels indexed:** Towards Eternity and Maybe God Podcast join
  the argument corpus with 276 newly extracted arguments between them, on top
  of the nightly refreshes.

### Changed
- **Edition info pages moved out of Java into properties files** — the prose
  now lives beside the other i18n resources instead of inside view classes,
  and a build-time check script enforces the class-size rule that motivated
  the move.
- ELB's Chinese page uses the house term for the Textus Receptus.

### Fixed
- **Scroll position is kept when switching language** instead of jumping back
  to the top of the reader.

## [0.7.4] — 2026-08-28

### Added
- **Language-specific URL paths for every interface language.** `/fi`,
  `/reader/he` and friends are now first-class routes for all 13 locales,
  registered on the Vaadin router from the same `LocaleUtil.LOCALES` list the
  language dropdown uses — a locale added there gets its routes, its security
  whitelist entry and its sitemap rows automatically. The legacy `?lang=`
  format keeps working unchanged, and both land on identical pages with the
  rest of the query string handled identically.
- **Localized link previews.** OpenGraph/Twitter title and description are
  served in the language the URL names — both formats — with new translations
  for Finnish, Swedish, Russian, Chinese, Italian and Turkish (drafted, worth
  a native-speaker pass). Portuguese was removed: it was never an interface
  language.
- **Sitemap lists the language editions with reciprocal hreflang clusters.**
  Each variant of `/` and `/reader` carries `xhtml:link` alternates for every
  language plus `x-default` at the English base page, so the editions are
  marked as translations of one page rather than competing content.

### Changed
- **The language dropdown now prefers the path form.** On `/`, `/reader` or an
  existing language path it navigates to `/{lang}` / `/reader/{lang}`
  (preserving the rest of the query string); only routes with no language path
  variant fall back to `?lang=`.
- **`<html lang>` follows the active language** in the bootstrap HTML and
  after client-side switches; text direction handling is unchanged.
- **`/en` and `/reader/en` canonicalise to `/` and `/reader`** and are not
  listed in the sitemap — same English pages, and two self-canonical URLs
  would split ranking between duplicates.

### Fixed
- **`?lang=` link previews were always English.** The query string was being
  parsed out of `getPathInfo()`, which never contains one; the language
  parameter is now read from the request directly.

## [0.7.3] — 2026-08-28

### Added
- **Edition info pages for seven more editions, and two of them complete in
  every interface language.** The Textus Receptus and the Latin Vulgate now have
  a `/edition/:abbr` page in all thirteen locales; Diodati, Douay-Rheims,
  Elberfelder, the Free Bible Version, the Geneva Bible and the Hebrew Bible have
  their English page, with translations to follow. The Vulgate page is the
  lineage root the Douay-Rheims rungs point back to, which is the reason it was
  taken before the leaves.

  Translations are checked mechanically rather than by eye: `scripts/site/
  check_edition_info.py` compares every translated entry against its English
  original on fact count, hero-paragraph count, cross-link targets, reader href
  and every date carried over in any numeral form, and reports per-edition
  locale coverage. A page that silently loses a paragraph in one of twelve
  languages is exactly the failure nobody would catch reading it. Findings that
  are correct — a date written out in words, or in a language's own idiom — live
  in an acknowledged list, so a non-zero exit means something new drifted.

### Changed
- **Prod pins the app image by version instead of pulling `:latest`.** The compose
  file now reads `app:${APP_TAG:-latest}` and `.env` on the server carries
  `APP_TAG=<version>`, so a deploy pulls a tag the box has never seen. Three things
  follow. Staleness stops being possible rather than merely unlikely — `docker
  compose pull` did re-resolve `:latest` correctly, but nothing on the box could
  tell you which build was running, which is what the corpus block's `rm -sf` +
  `rmi` dance exists to answer. `docker compose ps` and `.env` now name the running
  version without asking the app, so a mislabelled badge can be checked against the
  box. And rollback becomes `APP_TAG=0.7.0` plus `up -d app` — a pull, since CI has
  been tagging every image with its pom version all along.

  This is also the structural half of the SNAPSHOT policy added in 0.6.8. That gate
  stops a snapshot being BUILT as `:latest`; pinning removes the ambiguity that made
  the gate necessary, because prod no longer follows a moving tag at all. `basex`
  still rides `:latest`: `build_basex_image.sh` pushes only that, so pinning the
  corpus needs the script to tag its image and a decision about what to version it
  by — the corpus has no pom.


## [0.7.2] — 2026-08-24

### Added
- **The Emphatic Diaglott interlinear (DIAGIL) joins the corpus**, in a new
  *Interlinear & Study Editions* section of the About page rather than in the
  grid of Bibles. Benjamin Wilson's 1864 volume prints two English texts, and
  they disagree at John 1:1 — the sublinear gloss reads *"and a god was the
  Word"*, Wilson's own Emphatic Version *"and the LOGOS was God"*. What is
  ingested here is the gloss, which is the column that gets cited, so it is
  labelled as a gloss and kept apart from the reading editions: a word-for-word
  alignment aid records how the Greek is assembled, not how the translator
  judged it should be read. Following its Griesbach base the edition omits
  Acts 8:37 and Luke 17:36; the numbering leaves those two places empty rather
  than closing the gap, so references still line up verse-for-verse against
  every other edition. 27 books, 7,955 verses.

- **Edition info pages for CUV, DBY and DIAGIL**, continuing the one-batch-at-
  a-time fill of `/edition/:abbr`. The **Chinese Union Version** (1919) —
  commissioned by the 1890 Shanghai missionary conference, twenty-nine years of
  committee work, the English Revised Version as its working base, and the only
  Chinese Bible here, so a Chinese interface reaches it through the catalogue
  scan rather than a curated pick. The **French Darby** (1885) — the Pau–Vevey
  Bible, finished three years after Darby's death, sibling to the Elberfelder
  already in the corpus and the text his English Old Testament was afterwards
  built from. And the **Diaglott**, carrying the John 1:1 divergence and the
  Watch Tower Society's acquisition of the plates in 1902 in the same terms the
  About page uses. No sitemap work needed: `SitemapController` generates
  `/sitemap.xml` from `EditionInfo.PAGES`, so a new entry is routable and listed
  at once.

- **Two ingest-QA tools**, both prompted by defects this import surfaced that a
  contiguity check cannot see — five chapter-end verse merges and two
  source-renumbered omissions. `scripts/bibles/compare_chapter_lengths.py` diffs
  per-chapter verse counts against a reference edition and should be run for
  every future ingest; `scripts/bibles/show_verses.py` prints a range from
  several editions side by side.

### Fixed
- **The served API docs were shipping incomplete.** The
  `javadoc-to-served-resources` goal exited 1 on an `<h2>` used inside a *method*
  comment in `SourceCatalog`, where javadoc's implicit preceding heading is
  `<h3>`; the eight other `<h2>` uses in the tree are class-level and legal.
  Neither `mvn compile` nor `mvn test` sees this, because javadoc only runs
  under `-Pproduction` — the same gap that let a stale `@param` ship green on
  0.6.0. Caught by running the production build before tagging.

- **The Finnish footer no longer leaves *public domain* bare.** It read
  *"Pyhät tekstit ovat public domain tai lisensoituja"*, an uninflected English
  noun phrase in the predicative slot beside a properly inflected partitive
  plural; it now uses *vapaasti käytettävissä (public domain)*, the phrasing the
  file had already settled on elsewhere. *tekijänoikeudeton* was considered and
  rejected — a third Finnish term for one concept, heavier in register, and it
  says "has no copyright", which is wrong for texts whose copyright was waived
  rather than absent.

## [0.7.1] — 2026-08-22

### Fixed
- **FB1776 now says what it actually is.** Both Finnish philologists who have
  read the site asked which 1776 this was before they asked anything else, and
  the answer is that it is not the 1776. `@source` on the edition is a
  SourceForge project; the decisive test is the letter **w**, which old Finnish
  orthography uses for *v*: FB1642 has it in 26,862 verses and AGR1548 in 7,612,
  while FB1776 has it in **1 of 31,102**. The pages now carry a "Text used here"
  row — the row AGR1548 and FB1642 always had and this one conspicuously did not
  — naming it a modernised text of unrecorded editorial history, and a closing
  paragraph that gives the count, says plainly that 1776 modernised 1642 and
  this text was modernised again afterwards, and points to the National Library's
  page images of the real thing. All 13 languages. Raised independently by
  **Tanja Toropainen** (Turun yliopisto) and **Maria Lehtonen** (Kotus).

### Added
- **1685 and 1758 were not spelling reforms, and the chain now says so.** All 13
  FB1776 pages named only "the Florinus revision of 1683–85" as the step between
  1642 and 1776, which read as an orthographic staging post. The "Preceded by"
  row now names both intervening editions — 1685 (Florinus) and 1758 (Lizelius)
  — and the prose records that Henrik Florinus, provost of Paimio, knew Hebrew
  and Greek and *"korjasi Raamatusta niin kieltä kuin asiasisältöä"*: he moved
  the wording further than the orthography. That is the opposite of what the page
  implied. From Lehtonen, who supplied facsimiles of both editions; the claim
  that the intervening editions translated from the original languages is
  attributed rather than asserted, since VVKS confirms it of Florinus but is
  silent on Lizelius.

- **Agricola was not the first Finnish written, only the first printed.** All 13
  AGR1548 pages now name the four manuscripts that carry Finnish Bible passages
  — Codex Westh, Uppsala codex B 28, the Kangasala missal, the Uppsala fragment
  — and Pirinen's 1988 demonstration, from their Swedish sources, that parts of
  them predate anything Agricola printed, perhaps written in the 1530s. The same
  pages now note that Swedish scripture had been in print since the Swedish New
  Testament of **1526**, so a Swedish rendering was at hand from the first — the
  page previously named only Vasa's Bible of 1541. Both from Lehtonen.
- **The Sorolainen committee joins the 1642 story.** All 13 FB1642 pages record
  the translation committee appointed in **1602**, probably under Ericus Erici
  Sorolainen, Bishop of Turku, which produced drafts that were never assembled
  into a Bible and are largely lost, though the work is thought to have told on
  the 1642 translation. From Lehtonen; verified against VVKS, whose own hedges
  (*todennäköisesti*, *ilmeisesti*) are carried over rather than flattened.

- **Type a chapter number instead of clicking through.** The chapter label
  between ‹ and › is now the jump box: click it, type 23, press Enter. Asked for
  by **Maria Lehtonen** (päätoimittaja, Vanhan kirjasuomen sanakirja, Kotus),
  who could only reach the site on a phone, where stepping arrow by arrow
  through a long book is the alternative and the verse-search box at the top
  does not announce what it is for. The typed number is checked against
  `chapterNumbersForBook`, so typing 1 into Agricola's Exodus says the edition
  does not print that chapter rather than doing nothing — the same failure the
  book dropdown had before 0.7.0. It stays a label at rest and swaps to a field
  on click, because a text box sitting between two arrows looks like a form.

- **A line of thanks on the pages an outside reader improved.** A quiet italic
  line under the facts strip, in the page's own language, on the 41 pages that
  have been corrected from outside — AGR1548, FB1642 and FB1776 in all thirteen
  languages, KR3338 in two. It is a THANKS and not a credential: no titles, no
  institutions, no "reviewed by". They sent corrections, not endorsement. The
  wording is generic until Toropainen and Lehtonen have each been asked whether
  they would rather be named; the field takes the finished sentence, so agreeing
  is a one-line change per page and declining costs nothing.

- **The lineage ladder now runs both ways.** A column could open the texts its
  edition stands on, beneath each verse; it can now also open the texts that
  stand on IT, above. Reading Agricola, the newer Finnish Bibles appear over him
  in the order they were made — so the column reads down the page as time runs
  forward, with the chosen edition keeping its place in the middle.

  **One signed axis, the same two chevrons** (Christa's design): `rungDepth`
  runs from −descendants to +ancestors, ▾ increments and ▴ decrements. So ▴
  closes the open ancestors one at a time and then, once they are all shut,
  carries on in the same direction into the newer translations. No third and
  fourth button in a header that already holds eleven controls, and nothing to
  learn — the gesture means "move up the ladder", not "operate a widget".

  **The original-language floor is deliberately NOT inverted.** Walking down,
  every Bible is also shown the WLC and the TR as unattested *witnesses*, marked
  as such, because every translation ultimately stands on the Hebrew and the
  Greek. Mirrored, that would make the WLC the ancestor of nearly the whole
  corpus and turn an honest hedge into a false claim, so descendants follow
  recorded `@basedOn` only. Every rung on that side is therefore attested, and
  the marked rendering never appears there.

  Fan-out was the other risk and the data settles it: inverting the curated map
  gives TR four children, LUT1545 three, WLC three, and one or none for
  everything else — the full descendant walk from TR is 11 rows across four
  generations, the same order as the ancestor side.

  **Known limitation:** the link grammar's `companion=N` is `>= 1`, so a
  descendant depth has no representation in a shareable URL. It is dropped
  rather than encoded as its opposite — a link that opened the wrong half of the
  ladder would be worse than one that opens none of it. Widening the grammar is
  a separate decision.

---

## [0.7.0] — 2026-08-20

> Released as 0.7.0, not 0.6.8. `v0.6.8` had already been tagged at a
> `-SNAPSHOT` commit earlier the same day and was built and deployed from there;
> GitHub's tag protection refuses to move a tag once pushed, so the number was
> retired rather than rewritten. `v0.6.8` remains in the history, pointing at
> the FB1642 fix with a snapshot version in its poms. The minor bump also
> reflects the size of 0.6.7 and this release together — muted voices, Agricola's
> 685 liturgical verses, and downloads that are authentic copies.

### Fixed
- **Only a release version can be published as `:latest`.** Prod pulls
  `app:latest`, so whatever CI tags `:latest` is effectively deployed — and
  between releases the poms carry `-SNAPSHOT`, which meant a routine
  `--ref main` run would publish in-development code to the public site under a
  version number that identifies nothing. The workflow now reads the pom version
  and refuses the build unless it is exactly MAJOR.MINOR.PATCH. An allow-list
  rather than a block on `-SNAPSHOT`, because a block-list only stops the
  spellings you thought of — a pom typo'd `0.7.1-SNAPSOT` is not a snapshot by
  that test, and would have been published as `:latest` and deployed. Prod images
  are built from the tag (`--ref v0.7.0`). A deliberate throwaway image is still
  possible via the new `allow_snapshot` input, which publishes the version tag
  ONLY — never `:latest` — and skips the deploy step, so prod cannot pull it
  even by accident. Written down as policy in the versioning notes below and in
  COMMANDS §6, because a rule that lives only in a habit is not a rule.

- **FB1642 no longer claims a step that moved to Agricola.** The facts strip's
  "In this corpus" row read "the third rung of the Finnish chain, and the point
  where it crosses into German" — true until AGR1548 was inserted below it in
  0.6.5, after which the crossing is AGR1548 → LUT1545 and Agricola's own
  "Preceded by" row says so. Two pages were claiming the same step. All 13
  variants now carry the chain, as their AGR1548 and FB1776 siblings already did.
  The prose had been corrected in 0.6.5; only the facts row was missed.
- **Agricola's Exodus is 15, 19, 20 and 32** — a chapter number guessed from a
  count rather than read from the data, corrected in the 0.6.7 notes and in the
  `ColState` javadoc.

---

## [0.6.7] — 2026-08-20

### Added
- **Agricola gains 685 verses — and Genesis.** Tanja Toropainen pointed out that
  *Mikael Agricolan kieli* (SKS 1988) lists Bible passages Agricola rendered
  outside his scripture volumes; the material turned out to be sitting in the
  saved VKS pages already. The converter now GAP-FILLS from his Rukouskirja
  (1544), Käsikirja and Messu (1549) and Abc-kiria under two rules that keep it
  honest: a liturgical reading is used **only** where the scripture volumes have
  no verse at that address, so a translation is never displaced by its
  liturgical use; and **only** from a run of three or more consecutive verses,
  since continuous scripture comes in runs while a verse quoted inside a prayer
  stands alone. That admitted 685 verses in 58 runs and rejected 56 isolated
  quotations. AGR1548 is now **13,616 verses across 71 books** (was 12,931/53),
  including the chapters printed as "Genesist mutomat Lughut" — Genesis 1–4, 22,
  28 and 32 — plus 2 Samuel 22, Leviticus 26, Nehemiah 9, Song of Songs 2 and a
  good deal of the deuterocanon. Every borrowed verse is listed with its source
  work in `agricola_flat-gapfill.json`.

- **Readers can mute a voice.** Any commenter — an imported channel or another
  reader — can be silenced from the card it speaks on, and the mute holds across
  the inline verse bubbles and the comments panel alike, so the voice stops being
  encountered rather than merely filtered out of a list. Preferences shows the
  whole list at once and is where a mute is taken back off it. Stored per account
  as a newline-separated list of display strings (V17) rather than a join table:
  a channel is not a row anywhere, so a renamed one should simply stop matching
  instead of dangling a foreign key, and newline rather than comma because
  channel names contain commas and apostrophes ("DAWAH BRO'S PODCAST") but never
  a line break. Two invariants live in code rather than in the schema — a
  reader's own comments are never muted, and an explicit `?comments=<channel>`
  deep link overrides the mute for that visit, so a channel's own outreach link
  never opens an empty reader.

### Changed
- **Downloads are now authentic copies.** `/download/{id}` strips three
  attributes from every document it serves: `@globalCanonicalSeq`,
  `@globalChronologicalSeq` and `@globalTanakhSeq`. Those are dense per-edition
  counters this site stamps onto each verse after ingestion (scripts/bibles/04–06)
  so the reader can open a verse window, keep two columns in step, and offer the
  chronological and Tanakh reading orders. No editor, translator or printer put
  them there, and their values encode our ordering decisions rather than anything
  about the text — so shipping them handed people a Lutherbibel with our numbering
  baked in and no way to tell it was ours. Whoever downloads a text now gets the
  text; a reading order is theirs to choose. Everything else stays, `@basedOn`
  lineage and source metadata included, because those are claims about the edition
  rather than about our plumbing. The strip is an XQuery `copy … modify delete
  node … return` against the attribute nodes, so the stored corpus is untouched
  and nothing inside verse text that merely looks like an attribute can be caught
  by it.

### Fixed
- **The Agricola pages no longer say Genesis is absent.** All 13 variants: the
  second paragraph is rewritten to explain the liturgical supplement and its two
  rules, name the Genesis chapters, and state plainly which books remain missing
  entirely (Judges, Ruth, 1 Chronicles, Esther). The "Contains" row of the facts
  strip follows. The old claim — that the book list "simply starts at
  2. Mooseksen kirja, because that is where Agricola's printing began" — was
  true when written and is now false.
- **Ezekiel 18:1 had a chapter argument glued to its front** since the original
  import ("Lue Ezech . xvj . Cap . Jalon Wertauxen Sanan…"). It now reads "Ja se
  HERRAN sana tapactui minulle / ia sanoi ." The bold-as-separator rule written
  for the gap-fill caught it; it was the only one of the 12,931 existing verses
  that changed, and `--no-gapfill` reproduces the previous file byte-for-byte.
- **Choosing a book did nothing for 25 of Agricola's 71 books.** The book
  dropdown opened every book at chapter 1 and stepped through chapters by
  counting to `count(rt:chapter)`. Both assume a complete Bible. Agricola printed
  selections: his Exodus is chapters 15, 19, 20 and 32, so chapter 1 resolved to
  no verse, `seqForBookChapter` returned -1, and the listener returned in silence
  — pick Exodus, nothing happens, no error. Reported by Christa, who found that
  going down the list from Genesis the first book that responded was **Nehemiah**;
  that is exactly the first book after Genesis that has a chapter 1. Stepping was
  broken by the same assumption from the other end — chapter 15 tested as past the
  end of a four-chapter book, so *next* leapt to the following book and *previous*
  asked for a chapter number that was really a count. The book list now carries
  each book's real chapter numbers, a book opens where it starts, and prev/next
  walk the chapters that exist. `hasBookCode` replaces a "does chapter 1 have
  verses?" test that made lineage rungs step over books they do have.

- **Agricola gains its chronological and Tanakh reading orders.** AGR1548 had
  `@globalCanonicalSeq` on all 13,616 verses but neither of the other two — steps
  05 and 06 were never run after the re-import, so both alternate orders fell back
  to canonical. Now stamped: 13,405 verses chronological, 13,370 Tanakh. What
  stays unstamped is honest and falls back to canonical by design — the
  deuterocanon, which the chronological plan does not cover, and **Habakkuk 4**,
  which is not an import error: Agricola's Habakkuk runs to four chapters, modern
  chapter 2 being split after verse 4 (ch.2 = vv.1–4, ch.3 = vv.5–20) so that the
  prayer lands as ch.4. The 1642 Biblia divides it identically, and the verse
  numbering stays modern throughout, so nothing is lost or doubled.

- **NBLA had no canonical sequence at all** — 31,090 verses, every one unstamped,
  while its chronological and Tanakh stamps were both present. Canonical is the
  default order, so every verse collapsed to the flat `10000000` in `seqLet` and
  the edition opened wherever the tie-break landed: the GNV-opens-at-1-Peter bug,
  live, on an edition nobody had thought to re-check. Found by counting all three
  attributes across all 36 bibles while chasing the Agricola question. Now
  stamped 1..31090.

- **The chronological book list no longer scrambles unstamped editions.** In
  chronological mode the sidebar ordered books by `@globalChronologicalSeq` with
  no fallback, so an edition whose verses carry none — `xs:integer(())` is the
  empty sequence, and every book ties — was listed in whatever order the tie-break
  surfaced. It now falls back to `@globalCanonicalSeq`, as the chapter-chunk and
  streaming paths already did and as the Tanakh branch beside it already did. The
  stampers resolve book names through an English and Spanish map only, so this is
  the normal case for the Finnish editions, AGR1548 among them.

- **Round-2 review corrections** (Toropainen, 2026-08-15). All 13 FB1642 pages
  now date Agricola's Old Testament pieces to the 1550s. All 13 FB1776 pages
  name **Anders (Antti) Lizelius** as the reviser, and his earlier Bible of
  1758. Finnish-only: a missing comma on fi/FB1776, and *työskenteli* → *työsti*
  on fi/KR3338.

---

## [0.6.6] — 2026-08-13

### Fixed
- **Agricola's page corrected by an Agricola scholar.** Tanja Toropainen
  (Turun yliopisto, co-compiler of *Agricolan sanakirja*) reviewed the AGR1548
  page and sent six comments; three were factual and are now applied to all 13
  language variants, prose and facts strip alike:
  - **The Rukouskirja of 1544 joins the story.** The page jumped from the
    ABC-kirja straight to the New Testament; the prayer book sat between them,
    was substantial, and already carried psalm translations — so Agricola was
    rendering psalms seven years before the 1551 Psalttari.
  - **Erasmus's Latin, not only his Greek.** The *Novum Instrumentum* printed
    Erasmus's own Latin beside the Greek and Agricola worked from both. The
    lineage stamp is unchanged — TR remains the Greek witness, VUL the Latin
    one, and Erasmus's Latin is not a corpus text.
  - **Kustaa Vaasan Raamattu 1541** — the complete Swedish Bible, not "the
    Swedish New Testament" as the page had it. This also brings AGR1548 into
    line with the FB1642 pages, which already named Vasa's Bible correctly.
  Two further fixes are Finnish-only, being points of Finnish usage: the
  original title is no longer inflected (*Uuden testamentin (Se Wsi
  Testamenti)*), and *häneltä* is now *Agricolalta*. Her one compliment —
  that the page credits Agricola's Turku circle rather than casting him as a
  lone worker — is left exactly as it stood, in all thirteen languages.

---

## [0.6.5] — 2026-08-10

### Added
- **AGR1548 edition pages in all 13 UI languages** — the all-13 rule honoured
  on day one. The pages tell the story the corpus now shows: the first printed
  Finnish, the honest Genesis gap, and the triangulated parents. The About-grid
  info icon appears on the Agricola card in every language, and the generated
  sitemap gains the 13 URLs automatically.

### Fixed
- **The neighbours now tell the truth about Agricola.** All 13 FB1642 pages:
  "the chain here records only the Luther" corrected (it records Agricola's
  Finnish beside him), the "Preceded by" fact now links to [[AGR1548]], and the
  closing paragraph — which claimed FB1642 → LUT1545 was the corpus's only
  vernacular-to-vernacular step — rewritten, since that distinction moved one
  rung down to Agricola. KR3338 (en, fi) chain walk-down updated; every
  "FB1642 → LUT1545" chain string across the pages now reads
  "FB1642 → AGR1548 → LUT1545" (15 sites).

---

## [0.6.4] — 2026-08-10

### Added
- **Agricola in the corpus — the oldest printed Finnish there is.** Mikael
  Agricola's translations from the Kotus VKS corpus: the complete Se Wsi
  Testamenti (1548), the Dauidin Psaltari (1551, all 150 psalms), the Weisut
  ja Ennustoxet selections and Ne Prophetat (1551–52) — 12,931 verses across
  53 books, imported as `bible-fi-1548` / AGR1548. No Genesis, because he
  never translated it: coverage gaps are the historical record, per the
  2026-08-05 decision, so the book picker honestly starts at 2. Mooseksen
  kirja. New converter `02_convert_vks_agricola.py` handles the A-volume
  marker scheme, bold-set chapter arguments (except Ps 72:20, the one all-bold
  verse), the title-page rhyme, and Agricola's chapter divisions drifting from
  modern versification.
- **The Finnish lineage ladder is now SIX rungs**: KR3338 ← FB1776 ← FB1642 ←
  AGR1548 ← LUT1545 ← {TR, WLC}. FB1642 gained Agricola as its main-line
  parent (the 1642 committee revised his Finnish), and AGR1548 records the
  triangulation his prefaces describe: Luther's German, Erasmus' Greek and
  the Vulgate. About page: AGR1548 card added and the lineage-chains display
  now shows the full Finnish chain.

---

## [0.6.3] — 2026-08-09

### Added
- **Biblia 1642 and Luther 1545 in the corpus.** The first complete Finnish
  Bible (Kotus VKS corpus, 35,545 verses incl. 12 deuterocanonical books,
  authentic 1642 orthography and virgule punctuation) and the 1545 "Ausgabe
  letzter Hand" Luther (gratis-bible OSIS, modernised spelling, 31,170 verses).
  The Finnish lineage ladder now runs five rungs:
  KR3338 ← FB1776 ← FB1642 ← LUT1545 ← {TR, WLC} — and the German ladder gained
  LUT1912 ← LUT1545. New generic OSIS→flat converter
  (`02_convert_osis_flat.py`) and VKS converter (`02_convert_vks_biblia1642.py`).
- **Translator notes as comments — the LUT1912 footnotes.** The OSIS converter's
  `--notes` flag writes the `<note>` subtrees it strips out of scripture to a
  ledger (`transcripts/notes-lut1912.json`, 85 notes, deterministic `cmt_` ids
  from `mint_note_id`); the new `NotesSeeder` seeds them as comments owned by a
  locked "Lutherbibel 1912" system account, through the same V14 merge as
  arguments. V16 adds `comments.seed_ledger` so each seeder's merge and orphan
  sweep touch only its own ledger's rows — without it the two seeders would
  delete each other's comments on every boot. Notes are edition-BOUND on the
  read side: a 1912 gloss renders only under a LUT1912 column, while user
  comments and channel arguments keep their V5 edition-independence.
- **Edition info pages, written.** WLC in all 13 UI languages; FB1776, FB1642
  and LUT1545 in all 13 from day one (the all-13 rule); KR3338 in Finnish. The
  About-grid info icon now links to the current UI language's page when it
  exists, falling back to English (`EditionInfo.slugFor`).
- **`/admin/accessors`** — master-detail admin over users and groups: invites
  via claim-by-reset link, the role ladder, group CRUD with label and
  description (V15; `AccessorAdminService`).
- **`sitemap.xml` is generated** (`SitemapController` + test) from
  `LandingPage.PAGES` + `EditionInfo.PAGES` — every page family added since the
  static file was written had been silently missing from it; the static file is
  retired.
- **Edition info pages are per-language.** `/edition/WLC` stays English with no
  language segment (so published URLs need no redirects) and a translation lives
  at `/edition/fi/WLC`. `EditionInfo` gained `lang` and `slug`, `PAGES` is keyed
  by the slug — so `forPathInfo` is unchanged, because the path remainder IS the
  key — and `variantsOf(abbr)` groups an edition's languages. `LandingSeoListener`
  builds the canonical from the slug and emits an hreflang cluster with
  `x-default`, but only where an edition exists in more than one language: a lone
  self-referencing alternate says nothing, and non-reciprocal hreflang is worse
  than none. Settled before the pages exist, because moving thirty URLs later
  means redirects and re-indexing.
- **`SiteHeader`** — the wordmark, version pill, environment chip and language
  selector, shared by `LandingPageView` and `EditionInfoView`. Four views had
  grown their own nav bar and drifted: the landing and edition pages carried
  neither the version pill (contradicting `BuildInfo`'s own contract, "the build
  identity every page shows") nor a language selector, which would have been
  absurd the moment a translated page existed. `ReaderView` keeps its own
  toolbar — it is not a content page.
- `EditionInfoPathTest` — path resolution, trailing slash and query handling,
  every map key reachable at its own slug, and `LandingPage`/`EditionInfo`
  claiming disjoint paths (the `/read/hi/` hole, pinned).
- `docs/printer-send-2026-08-04.md` — the five enquiries written out per
  recipient with per-time-zone send times.

### Fixed
- **About roster corrections** — FB1642 and LUT1545 cards added; BSB renamed to
  its actual "Berean Standard Bible"; licence chips corrected (BES CC BY 4.0,
  PDDPT and VBL CC BY-SA 4.0).
- **`/edition/:abbr` ignored `?lang=` entirely.** `LocaleInitListener` honours
  the parameter app-wide, but `EditionInfoView` never called `getTranslation` —
  every string was an English literal, so even the navigation stayed English.
  Now bundle keys (`edition.allTexts`, `edition.openReader`).
- **`/edition/fi/WLC` would have 404'd** whatever the map said: a plain
  `HasUrlParameter<String>` matches one segment. Now `@WildcardParameter`.

### Notes
- `COMMANDS.md` gained the third false-failure trap: `-Dtest=` from the repo
  root needs `-Dsurefire.failIfNoSpecifiedTests=false`, because the reactor also
  builds `ingestion` and a filter matching nothing there fails the build after
  `app`'s tests have passed.
- Poms bumped to `0.6.3-SNAPSHOT`. The post-release bump was missed after v0.6.1
  AND again after v0.6.2 — twice in one day, so it belongs in the release
  checklist rather than in anyone's memory.

---

## [0.6.2] — 2026-07-31

### Added
- **`cN.hl` — highlight passages in other books.** A reader link could already
  flash a range (`c1.ref=ISA.52.13-53.12`), but a `ref` carries one book code,
  so it could not say "open at Deuteronomy 34 and also light up Psalms 90 and
  91". `hl` is a separate comma-separated list of references, painted with the
  same flash and never scrolled to, so the anchor still decides where the column
  lands. It exists for the reordered editions: the passage worth pointing at is
  the one that MOVED, which is by definition in a different book from the one
  the link opens at — and widening `ref` into a cross-book range would have been
  forced to paint the anchor as well, which is the part that did not move.
  Capped at 12 spans; unparseable entries are dropped rather than fatal.
- **`/edition/:abbr` bibliographic info pages** (`EditionInfo`,
  `EditionInfoView`) — "who translated this, when, where, from what", reached
  from an ℹ️ on that edition's About card, shown only where an entry exists.
  A plain data map like `LandingPage`, and kept in its own route namespace so
  the two never tangle: landing pages are built to rank for a language-level
  query, these are interstitials for someone already browsing. First entries:
  **WLC** and **TR**. Filling the rest is a recurring one-at-a-time task,
  scheduled weekdays 09:00.
- **`scripts/build_edition.py`** — pours a whole edition, in any stamped
  ordering, into a printer's own template: InDesign Tagged Text, an HTML shell,
  or a .docx with their styles. IDML is refused by design (generating one means
  authoring their document rather than filling it). `--self-test` exercises
  every writer without BaseX or a template, so a later failure is provably the
  corpus or their file. Docs: `docs/printer-templates.md`.
- **`docs/printer-outreach.md`** — printer targets, both enquiry emails, and a
  send log.

### Fixed
- **`EditionInfo` was package-private** while both SEO listeners import it from
  other packages, so the tree did not compile. Written but never built — the
  third time this project has had Java that no build had seen.
- **`docs/link-format.md` was stale**: `cK.order` did not list `tanakh`, and the
  range section did not say a range needs a start verse — so `DEU.33-34` reads
  as "open Deuteronomy 33", not as a range. Both now documented, along with the
  constraint that highlighting needs a numbered mode, since the `v-CODE-CH-VS`
  span ids only exist when verse numbers are shown.
- **Printer's sample layout** (`scripts/build_print_sample.py`): two-column
  setting restored (`column-fill: auto` collapsed it to one), the credit note
  moved above the columns (a note after a fragmented multicol is always pushed
  to a fresh sheet), and chapter headings bound into the verse paragraph as a
  full-width inline-block, because Chrome ignores `break-after: avoid` inside
  multicol.
- **Cover claim withdrawn.** "Three Bibles you cannot buy" was false: chronological
  Bibles are a retail category and reader's editions without verse numbers are a
  mainstream line. Now "set to order", naming the competition and claiming only
  what is actually ours — the combination and the licence position.

### Notes
- `COMMANDS.md` gained a Tests section: `-Djacoco.skip=true` is required
  alongside `-Dtest=`, since the coverage gate measures the whole project and a
  filtered run always trips it; and `mvn compile` does not prove a production
  build.
- The post-release bump after v0.6.1 was skipped, so this work had been
  accruing against an already-tagged version number. Cut as 0.6.2 rather than
  moving the v0.6.1 tag, which is already pushed.

---

## [0.6.1] — 2026-07-29

### Added
- **Luther Bibel 1912** (German, public domain), 31,102 verses, with its About
  card and a `YEARS` entry. It had been configured in `bible-sources.yml` and
  sitting in the coming-soon badges since before the badges were written, never
  ingested. No `LINEAGE` entry: Luther worked from Erasmus's Greek and the
  Hebrew, neither present as his attested source, so the original-language floor
  is the honest ladder — the same shape as the NIV and NASB.
  First entry completed from `docs/bible-queue.tsv`, and the cheapest of the
  Tier 2 wins: Luther stands behind the German line and, through Gustav Vasa and
  Agricola, the Nordic ones, so it unlocks four future chains rather than adding
  one card.

### Fixed
- **`docs/bible-queue.tsv` rows were a column short.** Fourteen of eighteen
  omitted the `source` field, so `parents` slid into `source` and the notes slid
  into `parents`. Harmless while every affected row was `manual` and skipped, but
  the first one promoted to `ebible` would have finished by asking for a
  `LINEAGE` entry of "TIER 1. The KJV translators' instructed base text…".

### Notes
- The ingester builds document ids as `bible-<abbreviation>-<year>` and ignores
  the `id:` field in `bible-sources.yml` entirely. LUT1912 is the only edition
  whose abbreviation already contains its year, so it landed as
  `bible-lut1912-1912`. Cosmetic, and left alone: renaming means an XQuery update
  against the corpus or 1,190 more API requests, and nothing depends on the id —
  cards, source tokens and `/download` all key on the abbreviation.
- `stamp_chronological.py` reports 91 more verses stamped than an edition holds,
  on every edition. Confirmed benign: the plan interleaves parallel accounts
  (Chronicles beside Samuel and Kings, the Gospels against each other), 91 verses
  are covered twice, and the second position wins. Verified on LUT1912 —
  31,102 verses, 31,102 distinct sequences, highest sequence 31,193. The gaps do
  not affect ordering.

---

## [0.6.0] — 2026-07-29

### Added
- **Translation lineage has its own section on the About page.** The rungs were
  the site's most distinctive feature described in its least prominent
  typography — one 12px caption. The section now covers why translations are not
  independent witnesses, what the controls do, the year chips, the recorded
  chains as a monospace block, and the two things the caption omitted: that rungs
  render only in Verses and Titles modes (the likeliest way to conclude the
  feature is broken), and which editions actually have an ancestry.
- **NASB 2020** (API.Bible licensed slot 2), 31,073 verses, with its About card
  and a `YEARS` entry so its rung chip reads properly. No `LINEAGE` entry: like
  the NIV it works from critical editions not in this corpus, so the
  original-language floor is the honest ladder.
- **Download any public-domain edition as the corpus XML.** `GET /download` lists
  what is available; `GET /download/{id-or-abbreviation}` returns the document
  exactly as stored — books, chapters, verses and all three reading-order
  sequences, against `religious-text.xsd`. Not an export format and not a
  conversion: someone who wants the whole World English Bible as structured XML
  should not have to scrape it out of a web UI. Each public-domain card carries
  a `⤓ XML` chip; the About page explains it in plain language.
- **Qur'an — Yusuf Ali** now has a card. It was ingested and uncarded.
- **A ladder-first corpus expansion plan** (`docs/bible-expansion-plan.md`), a
  machine-readable worklist (`docs/bible-queue.tsv`) and a daily ingest job.
  The principle: the next text to add is the one that puts a rung under the most
  editions we already have.

### Fixed
- **The download gate refuses jurisdictionally qualified public domain.** Yusuf
  Ali is recorded as "Public domain in EU/life+70 & Pakistan …; US URAA copyright
  to 2033" — a prefix match on "public domain" would have served worldwide a text
  still in copyright in the United States. The endpoint cannot know where the
  requester is, so it now accepts unqualified public domain, or public domain
  followed by a parenthetical attribution, and refuses anything qualified by
  jurisdiction. Licensed texts could never be served; this closes the harder case.
- **The Sources table no longer names translations the corpus does not hold.** It
  read "NIV, NASB, NBLA (licensed)" when only the NIV had ever been ingested.
  Replaced the enumeration with a pointer to the per-card licence label, so it
  cannot drift again.
- **The card download chip stays inside its card.** It was positioned against a
  wrapper that stretched to the grid cell while the card sized to its content, so
  it drifted into the gutter on any card shorter or narrower than its column.

---

## [0.5.0] — 2026-07-28

### Fixed
- **Canonical URLs are self-referencing** — `SocialPreviewInitListener` emitted one
  hardcoded `<link rel="canonical" href="https://common-root.org">` on *every*
  route, so `/reader` — listed in `sitemap.xml` at priority 0.9 — declared itself a
  duplicate of the homepage. Google reported it under "Alternate page with proper
  canonical tag" and never indexed it: the sitemap asked for indexing, the canonical
  forbade it, and the canonical wins. The canonical and `og:url` are now built from
  the request path.
- **No more duplicate head tags.** `configurePage` and `SocialPreviewInitListener`
  both wrote a description and a full og:/twitter: set, so every non-landing page
  shipped two conflicting `<meta name="description">` tags (Google picks one at
  random) and a duplicate og: block. The app shell now sets only `<title>`; the
  listener owns the rest. Vaadin's `AppShellSettings.addMetaTag` can only write
  `name="og:..."`, which OpenGraph parsers ignore — those tags were dead weight
  that existed only to collide with the correct `property="og:..."` ones.
- **Auth and account routes are `noindex`** (`/login`, `/register`, `/forgot`,
  `/reset`, `/verify`, `/profile`, `/preferences`, `/admin/**`) instead of being
  canonicalised to the homepage, which was a *wrong* canonical rather than merely a
  redundant one. `/reset` and `/verify` carry single-use tokens that have no
  business in a search index.
- **The two SEO listeners can no longer clobber each other.** Both are unordered
  `VaadinServiceInitListener` beans and Vaadin's Spring integration collects them
  with `getBeansOfType(..)`, which does not honour `@Order` — so the landing pages
  kept their own canonicals purely by component-scan luck, the same failure mode
  found on 2026-07-26. They now have disjoint path domains resolved by one shared
  method (`LandingPage.forPathInfo`), so order is irrelevant. That also closed a
  hole where `/read/hi/` (trailing slash) was claimed by neither listener and got
  no canonical or preview tags at all.

### Added
- **Open Passages recognises Qur'an references** — a generic marker word
  ("Q 19:21", "Sura 2:255", "Surah 19:21") means "the numbers that follow are
  surah:ayah", so a Qur'an reference typed into the box now resolves instead of
  being looked up as a Bible book. Language-independent: the markers are a flag,
  not localized book names. Surah NAMES ("Al-Baqarah 2:255") still need a
  114-entry lexicon and are filed as a follow-up.
- **Landing pages are readable without JavaScript** — Vaadin Flow serves an app
  shell, so until now a `/read/:code` page reached any crawler that does not
  execute JS as a title and a meta description over an empty body. The single
  `Baiduspider-render/2.0` visit in the access log (2026-06-25) aborted part-way
  through fetching the Flow import bundle, so it almost certainly never rendered
  anything. Each landing page's heading, prose, script sample and reader link now
  ship in a `<noscript>` block in the served HTML.
- **`/read/zh` — Chinese Union Version landing page.** 和合本 (Simplified),
  written in Chinese, linking into the reader with `&lang=zh` so a Chinese-speaking
  visitor does not land in an English interface. Added to `sitemap.xml`.
- **Translation lineage rungs** — Bible columns can open, one rung per click,
  the translations the edition stands on beneath each verse (Christa's ladder
  idea via the Qur'an-companion mechanism): curated `@basedOn` chains
  (`patch_lineage.py`; WEB→ASV→RV→KJV→GNV→TR/WLC, DRA→VUL, KR3338→FB1776),
  an original-language floor (≈-marked WLC/TR witness rungs for every Bible,
  incl. chainless ones like NIV), content-aware stepping (silent rungs skipped
  per current book), per-verse pruning, cross-language ref-axis alignment, and
  `cK.companion=N` in links (legacy `1`/`true` unchanged). New
  `versesByCodeChapter` query; `SourceCatalog.Rung`; year-stamped rung chips
  ("GNV 1599", "≈ WLC 1008" — `@year`, `patch_lineage.py`) + attribution
  marks; tests for generations, floor, diamonds, cycles, link depth.

---

## [0.3.10 – 0.3.11] — 2026-07-24

*(spans two tags: the 0.3.10 ceremony skipped the changelog move, so its
entries accumulated here alongside 0.3.11's)*

### Added
- **Turkish (Türkçe) UI** — Turkish added as a new interface language
  (`LocaleUtil.TR` + `LOCALES`, `LanguageSelect` 🇹🇷). Full
  `translations_tr.properties` (all 277 keys) plus Turkish Bible book names
  and reference abbreviations (`booknames_tr` / `bookabbrev_tr` — modern
  Kutsal Kitap naming, with older Kitab-ı Mukaddes forms as parse alternates).
  About page gains a Turkish language label and a Kitab-ı Mukaddes (1941)
  text card. NOTE: the Turkish Bible text (`bible-tr-1941`) still needs the
  local BaseX ingest — command in CLAUDE_NOTES handover.
- **About-page text cards link into the reader** — every card in the
  "Available Texts" listing is now an anchor opening the reader on that
  source (`/reader?c1.src=<token>`, the source abbreviation lowercased).
  Hadith cards link to the Arabic base with the English companion visible
  (`buk-ar` + `c1.companion=1` — their displayed "BUK" label is not a corpus
  abbreviation). New `about.texts.openInReader` tooltip key (English base
  only) and a hover lift on the cards (`.text-card-link` in styles.css).
- **About-page mode cards and problem examples link into the reader** — each
  display-mode card opens the reader on John 1 in that mode
  (`/reader?c1.ref=JHN.1&c1.mode=<token>`), and each "When Divisions Distort
  the Text" reference chip opens its passage with the WHOLE context span
  highlighted, not one verse (Isaiah 52:13–53:12, Romans 7:21–8:2,
  Philippians 4:10–13, Jeremiah 29:10–14, John 11:32–37). All src-less links.
- **Reader links accept verse ranges** — `cK.ref=ISA.52.13-53.12` (or
  `PHP.4.10-13` within a chapter; `Q.2.255-260` within a surah) opens at the
  start and flashes every verse in the span (`ReaderLink.Ref` end fields +
  `ReaderView.flashRange`). Malformed/backwards ends drop leniently.
- **Src-less reader columns now honour the UI language** — the default
  edition resolves preference → an edition in the UI language → NIV
  (`ReaderView.defaultBibleToken` + `bibleTokenForLanguage`; curated picks
  for en/es/fi/he, catalogue scan by `@lang` otherwise). A Finnish visitor's
  About-page links open Kirkkoraamattu, not NIV.

### Fixed
- **Overview PDF link falls back to English** — the About page built the
  overview-PDF href straight from the UI locale with no existence check, so
  locales with no bundled overview doc (Hebrew, Hindi, and now Turkish) 404'd.
  It now serves the localized PDF only when it is actually on the classpath,
  otherwise the English overview.
- **Edits survive restarts** (V14) — the startup reseed used to delete and
  rebuild every channel comment from the ledger, silently wiping any
  correction a claimed channel had made. Reseeding is now an edit-aware merge:
  human-edited rows are preserved verbatim (their edit outranks the ledger,
  including future re-extractions), un-edited rows update in place (custom
  ACLs and permalinks survive), deliberate deletions leave tombstones and stay
  dead, and edited rows dropped from the ledger are kept.
- **Sign-in returns to the reader reliably** — three separate holes: the
  post-login forward consumed its stashed link before checking authentication
  (burning it if About rendered once unauthenticated); an empty reader with no
  configured column built no link at all, so signing in from it landed on
  About; and an open-but-unfiltered comments panel was absent from the link
  grammar, so it was lost on return. The stash is now consumed only when
  forwarding, a bare reader stashes `/reader`, and `?comments=*` carries "panel
  open, no commenter chosen" — which also makes copy-link round-trip it.
- **Sign-out keeps you reading** — signing out inside the reader returned to
  the landing page, discarding the columns, even though reading needs no
  account. Logout now lands back on the reader (as a guest) when that's where
  it was triggered, via the `Referer` header — accepted only when same-origin
  and pointing into `/reader`, so it can't be turned into an open redirect.
- **Opening the comments panel is instant** — the unfiltered view renders the
  first 50 cards with a "pick a commenter" note instead of all ~3.6k; every
  filtered view remains complete. (The commenter box filters as you type.)

---

## [0.3.9] — 2026-07-19

### Fixed
- **Saving a comment no longer freezes the panel** — the post-save rebuild
  used to re-render every comment unfiltered (~3.6k cards + a full permission
  evaluation) and dropped the channel selection. The rebuild now carries the
  filter through: only the selected channel's cards re-render and the
  selection survives. Opening the panel for a channel also renders filtered
  from the start.

---

## [0.3.8] — 2026-07-18

### Fixed
- **Claim-by-reset actually admits you** — completing a password reset now
  marks the account verified (a reset proves control of the mailbox). Channel
  accounts are seeded unverified and receive no verification mail, so before
  this a claiming channel set a password and was then refused at login as
  unverified. Found by walking the outreach claim journey before sending.

---

## [0.3.7] — 2026-07-17

The comment-editor release: one dialog for composing and editing comments —
private-first, passage-aware, and gated by the access model — plus quality of
life across sign-in, headers, and the build stamp.

### Added
- **Return to the reader after sign-in** — signing in from the reader now lands
  back in the exact view you left (columns, refs, comments panel, focused
  comment): the live reader link is stashed at Sign-In click and replayed
  after login.
- **Private drafts surface for their author** — your own comments (including
  unpublished drafts) now show under every verse they cite, tagged "Your
  comment" / "Private draft". A private multi-verse draft thus works as a
  personal note spanning passages; nothing is shown to anyone else. Clicking a
  verse number opens the comment editor directly — PRIVATE-FIRST (new comments
  start unpublished; the public checkbox is the deliberate act). Personal
  notes keep their 📝 badges but lose the front-door role.
- **One comment editor, ACL-aware** — composing and editing share a single
  dialog: content on top, the cited passages below as removable chips with a
  free-text add parser ("John 1:1, Rom 9:5") and the external video link.
  Composing anchors on the verse being commented (fixed chip; extra passages
  persist on save). A ✎ on comment cards opens the same dialog for callers
  whose effective level reaches write (channel members on their org's imported
  arguments, owners, admins); delete at level delete with an inline confirm.
  Permissions are bulk-evaluated per rendered list (one group-closure
  computation, org-ACL lookups cached per channel), and the service re-checks
  every save. Public flag and moderation state are untouched by shared edits.
- **Version + environment on every page** — the version pill and env chip are
  now one shared component (`BuildInfo`) shown on all views: reader, About,
  Profile, Preferences, Access control, and the auth pages.

### Fixed
- **Toolbar shows the display name** — the signed-in label in the reader
  toolbar now resolves the user's display name ("Test Admin"), falling back to
  the email prefix only when none is set. Previously it always showed the
  email prefix regardless of the profile setting.

### Changed
- **Roles are not ACL accessors** — privileges are granted to accessors only
  (users, groups, world/signed-in/owner). The pickers no longer offer roles,
  and `accessorKeys` emits no role keys, so a legacy `role` ACE is inert. The
  role ladder acts solely on its own system-wide plane.

---

## [0.3.6] — 2026-07-16

The access-control release: a Documentum-inspired permission model — a
system-wide role ladder co-existing with per-object ACLs — plus the admin
tooling to manage it.

### Added
- **Role ladder** (V11) — cumulative system-wide roles
  `consumer ⊂ contributor ⊂ admin ⊂ superuser`. Consumers keep private drafts;
  contributors publish; admins moderate and grant contributor; the superuser
  grants admin and holds full control of every object.
- **Per-object ACLs** (V11) — named ACLs with accessor entries
  (world/signed-in/owner/role/user/group) on the reduced Documentum ladder
  `none < browse < read < write < delete`. Resolution chain per comment:
  custom ACL → org default → system default; effective access =
  max(role plane, ACL plane). Owner floor is **read** (default delete,
  reducible for retention); nested groups resolve transitively.
- **Comment permissions dialog** — a 🔒 on each comment card opens its
  effective ACL; owner/admin edits copy-on-write a per-comment custom ACL.
  Searchable add-accessor picker excludes already-granted principals.
- **Admin ACL manager** (`/admin/acls`, admins) — master-detail editor for the
  shared/named ACLs (system default + each org's default): change grants,
  rename labels, and create a new ACL attached to an org. Linked from Profile.
- **Deferred org provisioning** — channels get a member group + org default
  ACL only when claimed (dev seeds provision all channels for testing).
- **Dev test logins** (`religioustext.dev.seed-testdata=true`) — one user per
  role, a claimed channel, and a nested sub-team group.
- **Environment badge** — a chip beside the version in the reader toolbar and
  About nav: amber `DEV` off prod, green `PROD` on `-Pproduction` builds.
- **Served API reference** — Javadoc is generated into the jar at package time
  and served at `/docs/api`, linked from the About page and the technical doc.

### Changed
- **`channel-owners` → `channel-members`** — the per-channel group grants
  privileges over the org's content but owns nothing; the machine key, service
  methods, and docs now say so. Groups display as "&lt;Channel&gt; members".
- **Parameter naming convention** — every method/constructor parameter across
  both modules now carries an `a`/`an`/`the` article (record components
  exempt), matching the project's `final`-everywhere style.
- **Argument ledger** — refreshed extraction (5,580 useful arguments) with
  deterministic `cmt_` ids backfilled.

### Fixed
- **Schema-wide id types** (V12, V13) — all `CHAR(40)` id columns from V1/V11
  converted to `VARCHAR(40)` so Hibernate schema validation (`ddl-auto:
  validate`, newly enabled) passes; the whole schema is now uniform.
- **Preferences page** — the three checkboxes align with the fields above
  instead of stair-stepping.

---

## [0.3.5] — 2026-07-09

### Added
- **Per-channel comment ownership** (V10) — each imported channel gets a
  system-owned account that owns its comments; `is_system` flags it and
  claim-by-reset (password reset to the channel's outreach address) is the
  claim path.
- **Prod SMTP wiring** — transactional mail configured for the deployed
  environment.

---

## [0.3.4] — 2026-07-07

The outreach release: every comment is now a stable, shareable destination, and
links land exactly where they point.

### Added
- **Comment permalinks** — every comment carries a stable public id
  (`cmt_…`, minted deterministically in the extraction pipeline; V8). A 🔗
  button on each comment card copies `?comment=cmt_…`, which opens the reader
  with the comments panel filtered to that commenter, the card scrolled into
  view and flash-highlighted — the comment-level twin of a verse link's
  landing. Stale links degrade to the open panel with a notice.
- **Whole-view share links** — the toolbar Copy-link now captures the focused
  comment alongside the open columns, and refs are **verse-precise**
  (`PRO.16.32`, not `PRO.16`) for columns opened from comment refs, search
  hits, or verse deep links. "Open all → Copy link" reproduces the entire
  view: columns, panel, filter, flashed comment.
- **Per-channel comments deep link** — `?comments=<channel>` opens the reader
  with the comments panel pre-filtered to that commenter; the copy-link button
  and live address bar carry the panel state. Built for channel outreach.
- **User preferences** (`/preferences`, signed-in; V9) — default Bible
  edition, display mode, reading order, comments-panel and in-text-marker
  start state, preferred UI language, and "continue where I left off"
  (reopening the reader without a link restores the last position). Defaults
  apply to bare `/reader` and wherever a link leaves settings unspecified;
  comment references open in the preferred edition.
- **Link codec** — Tanakh reading order is now encodable (`order=tanakh`);
  a column with a ref but no `src` opens in the user's default Bible, so
  hand-written links like `?c1.ref=JHN.3` just work.

### Fixed
- **Deep links opened a chapter early** — the scroll-to-anchor was silently
  skipped on link entry (the view isn't attached during `beforeEnter`, so the
  page-scoped JS never ran). Element-scoped invocation fixes every shared
  link's landing.
- **Anchors landed under the sticky bar** — all jump paths (deep links,
  prev/next, sync) now compensate for the sticky header's live height; the
  chapter heading is fully visible after every jump.
- The comments toolbar toggle 💬 now reflects whether the panel is open.
- Pointer cursor on links and link-styled buttons.

### Pipeline
- The extractor mints permalink ids at entry creation (ref-guarded); the
  2026-07-07 backfill stamped 636 new entries (2,430 distinct ids, zero
  collisions). The argument seeder skips ref-less and duplicate-id entries
  and reports per-cause skip counts.

---

## [0.3.2] — 2026-07-04

### Added
- **Hindi UI language (हिन्दी)** — full locale bundle + localized Bible book
  names; Hindi book names parse in the multi-ref field. **Hindi Bible: IRV
  2019** (Indian Revised Version, CC BY-SA 4.0, Bridge Connectivity Solutions),
  ingested from the ebible.org USFM bundle.
- **Hebrew UI language (עברית)** — the app's second right-to-left locale; the
  whole interface mirrors. Localized book names (Tanakh + Delitzsch-tradition
  NT). **Hebrew whole Bible**: Masoretic OT + Delitzsch NT (Public Domain).
- **Matu Chin Bible (Baibal Olcim, Public Domain)** — the Bible in Matupi
  Chin (Myanmar); a genuinely public-domain edition serving a persecuted
  Christian minority, squarely on the reader's privacy-first mission.
- **Tanakh reading order** — a third global order alongside Canonical and
  Chronological: the OT in the Hebrew-Bible arrangement (Torah → Nevi'im →
  Ketuvim, Chronicles last; printed/BHS Ketuvim order), NT unchanged after it.
  New `stamp_tanakh.py`; `@globalTanakhSeq` stamped across all 29 Bible docs;
  order labels in all twelve locale bundles.
- **Native edition names** — every edition now presents itself in the language
  of its own text (כתב־יד לנינגרד, صحيح البخاري, इंडियन रिवाइज़्ड वर्ज़न…),
  with the previously stored English name kept in parentheses where one
  existed. Applies to Bibles, the Arabic Qur'an and hadith collections
  (`patch_native_names.py`). Book names inside a column likewise follow the
  text's language, not the UI language.
- **Language in the URL** — `?lang=fi` (any supported code) opens the app in
  that language; switching language writes the parameter back; the brand links
  home carrying the current language; copied deep links include it.
- **Live address bar** — the browser URL now continuously mirrors the open
  columns/position via `history.replaceState`, so the current view is always
  bookmarkable and shareable as-is.
- **USFM converter** (`scripts/convert_usfm_flat.py`) — stdlib-only
  ebible.org-USFM → flat-JSON bridge into the existing import pipeline;
  strips footnotes/cross-reference markup properly.
- **Self-hosted analytics (Umami)** — first-party, cookieless, no consent
  banner needed; shares the existing MySQL; served at analytics.common-root.org
  via Caddy. Tracking script injected only when explicitly configured — dev
  and test builds never track. (Infrastructure ships in this release; the
  dashboard/website-id activation is a separate ops step.)

### Fixed
- **Scriptio Continua destroyed non-Latin text.** The all-caps transform kept
  only ASCII `A–Z`, deleting Hebrew/Greek/Arabic/Cyrillic/CJK/Devanagari
  entirely — and silently dropping Finnish ä/ö all along. Now Unicode-aware;
  pointed Hebrew (WLC) renders as unvocalised consonantal text, which is what
  the ancient manuscripts this mode recreates actually looked like.
- **Chronological order missed the Gospels in three Spanish editions**
  (BES/PDDPT/VBL name them Mateo…Juan without the "San" prefix the alias map
  expected) and Song of Songs across the same docs; aliases fixed, docs
  re-stamped. Update timeout raised 30s→120s.
- **Book-name localisation could leak the JVM default locale** for languages
  without a booknames bundle; pinned to exact-language-or-English.
- **Hindi IRV first ingest replaced.** The wldeh-clone data embedded footnote
  text inline in verses and carried Hindi `@name` book names (breaking
  cross-edition sync); re-ingested clean from ebible USFM.
- **Synthetic demo comments purged** — 100 template users (@example.com) and
  their 1,794 comments, left over from February's comments-panel development,
  removed from the local database. The comments panel now shows only real
  transcript-derived content.

### Changed
- Original-language tier entries (`grc`/`he`/`la`) and Matu Chin now display
  flags + native language names instead of raw language codes.

---

## [0.3.1] — 2026-07-02

### Fixed
- **About-page documentation links 404'd for visitors.** The download links
  pointed at raw.githubusercontent.com, which stopped serving the public when
  the repo went private. The per-language overview PDFs and the technical PDF
  are now packed into the app jar at build time (`META-INF/resources/docs`)
  and served by the app itself at `/docs/...` — public, versioned with each
  deploy, no GitHub dependency. (`/docs/**` whitelisted in SecurityConfig;
  Dockerfile copies `docs/` into the build stage.)
- **i18n: 305 missing translations filled across the nine locale bundles** —
  most visibly the comments-panel chrome (English in 8 of 9 languages), the
  Simplified display-mode card, the hadith/LDS About sections, and the
  mode-history tooltips. Also refreshed 14 stale values: the Qur'an and hadith
  source rows still said "coming soon" in seven languages weeks after both
  went live. PDFs no longer render an empty Table of Contents heading
  (pandoc TOC field that headless LibreOffice never populated — dropped).

### Changed
- **Comments catalogue refreshed.** The local extractor ledger now stands at
  ~11.4k transcript arguments processed / ~3.2k useful; the new batch ships
  baked into the image and `DataSeeder` seeds it on deploy.
- **ReaderView decomposed** (internal, behaviour-preserving): `SourceRow`,
  `SourceCatalog`, `ColState`, `ReaderScrollController` and
  `VerseWindowRenderer` extracted with characterization tests; ReaderView
  ~2,600 → ~1,760 lines, back under the 2,000-line hard cap.

### Added
- Diaglott ingestion tooling: studybible.info interlinear fetcher, e-Sword
  `.bblx`/`.bbli` extractor, PDF OCR Wilson-column extractor (parked), and an
  `--allow-partial` flag in `import_json_bible.py` for NT-only editions. (The
  Emphatic Diaglott interlinear text itself lands in a future release.)

---

## [0.1.0 – 0.3.0] — June 2026 (changelog lapsed)

The changelog wasn't maintained between 0.0.1 and 0.3.0. That window shipped —
without per-release entries — the first Linode deploy, the HTTPS/domain cutover
to common-root.org, full-text search then multilingual search, UI i18n across
nine languages, the transcript-comments pipeline and reader comments UI, the
LDS standard works, original-language editions (WLC Hebrew, Textus Receptus,
Vulgate, Finnish 1933/38), and the chronological-mode work recorded below. See
git history for the detail.

### Fixed (chronological mode)
- **Forward fill walked through the whole Bible.** The MutationObserver scroll
  anchor was misdiagnosing every chronological forward fill as a backward
  prepend, because `reconcileSeparators` wipes and re-adds the top separator
  on every load. The re-added separator at `top.nextElementSibling` flipped
  `isTopPrepend` to true, triggering a 500 ms cooldown that throttled forward
  fill to one chunk every half-second — slowly grinding through all ~1,200
  chunks. Fix: the anchor now identifies a true prepend by looking for an
  added non-separator chunk at the top of the DOM. Separator-only mutations
  no longer trigger the cooldown path; forward fill proceeds at normal speed
  and stops when the bottom sentinel is far enough from the viewport.

### Added
- **Chronological reader mode — chunked lazy load.** Setting a column's order
  dropdown to "Chrono." now interleaves verses across books per the
  `globalChronologicalSeq` stamping: Psalm 3 sits between 2 Samuel 15:37 and
  16:1, Psalms 11 and 59 between 1 Samuel 20:42 and 21:1, Genesis 11 → Job →
  Genesis 12, etc. The load unit is a `(book, chapter)` chunk drawn from a
  ~1,200-row skeleton query (`listChronologicalChunks`); the existing
  `appendChapter` does the actual rendering. Same lazy-load mechanics as
  canonical (30 chunks in DOM, trim oldest / newest on overflow); same scroll
  observer, same chapter-visible broadcast for cross-column sync. Book
  separators are recomputed after every load via `reconcileSeparators` so a
  separator sits above every chunk where the book differs from the chunk above
  — including the chronologically-correct re-emission of "─── Genesis ───"
  when chronological order returns from Job back to Genesis 12. `chapterIsLoaded`
  now branches on order mode so cross-column sync correctly resolves
  `(book, chapter)` to a chunk-window check in chronological columns.
- Per-column chunk fetch is lazy: chunks are fetched once on the first switch
  to chronological, cached, and cleared on source change.

### Known limitations of chronological mode (v1)
- Same-book backward scroll has a ~28 px visual over-shift when the prepended
  chunk continues the same book as the existing top (e.g. Genesis 5 → Genesis
  4). This stems from `reconcileSeparators` removing and re-adding a separator
  of identical height in a way that the MutationObserver anchor double-counts.
  Cross-book backward (the dramatic moment) has zero shift. Polish for next
  release.
- Cross-column sync from a chronological column broadcasts `(book, chapter)`,
  which is unambiguous; a receiving chronological column finds the matching
  chunk via `chunkIndexOf`. Untested with two chronological columns side by
  side — likely works, test before relying on it.
- The prev/next chapter buttons in the per-column nav bar weren't reworked
  for chronological mode — they still walk `(book, chapter)` numerically.
  They mostly work for in-book transitions; they may behave oddly at book
  boundaries in chronological mode. Use natural scrolling instead for now.
- Apocrypha-only books (KJV / DRA / WEB deuterocanon) aren't covered by the
  chronological YAML — they fall back to canonical seq and sort to the end.

### Fixed
- `ingestion/pom.xml` parent version was `1.0.0-SNAPSHOT` when 0.0.1 was
  tagged — a slip in the 0.0.1 release pass. Brought into line with the rest
  of the multi-module build (now `0.0.2-SNAPSHOT` alongside root and app).

### Changed
- Adopted Maven `-SNAPSHOT` semantics for in-development versions (see
  "SNAPSHOT semantics" above). The version badge in the toolbar now reads e.g.
  `v0.0.2-SNAPSHOT` between releases, accurately distinguishing tagged releases
  from in-progress builds.

---

## [0.0.1] — 2026-06-06

First versioned snapshot. Captures the state of the reader after Spring
Security and the backward-scroll fix landed; serves as the baseline for
incremental feature releases.

### Reader
- Multi-column reader with per-column source, display mode, and order mode
  selectors; lazy chapter loading on scroll; book-change separators.
- Five display modes: Chapters & Verses, Chapters only, Verses only, Titles
  (section headings inline), Scriptio Continua (all-caps no-spaces).
- Per-column sync chain that keeps linked columns at the same passage; can be
  toggled per column.
- Backward-scroll fix using an in-flight `pendingPrev` guard cleared on the
  prepend-arrival mutation (rather than a fixed 300 ms timer that could trap
  the reader at the top after a fast up-scroll).
- Section-title rendering now only appears in Titles mode and is styled
  distinctly (bold + italic + 17 px + top border) so it doesn't collide with
  the chapter heading in other modes.

### Auth
- Registration, login (Vaadin LoginForm), email verification via token, and a
  profile page (`/profile`, `@RolesAllowed("USER")`).
- BCrypt password hashing; `DisabledException` blocks login until verification.
- `MailService` uses `JavaMailSender` when SMTP is configured, falling back to
  logging the verification link to WARN otherwise — so dev works without SMTP.
- Auth-aware reader toolbar: shows display-name link + Sign Out when logged in,
  Sign In + Create Account otherwise.

### Data
- Six Bible translations ingested: NIV, KJV, ASV, WEB, DRA, RVR09.
- 185,582 verses stamped with `@globalChronologicalSeq` (91 % of total).
  Interleaving validated: Psalm 3 between 2 Samuel 15:37 and 16:1, Psalms 11
  and 59 between 1 Samuel 20:42 and 21:1.
- Argument-seeding `DataSeeder` covering Islamic, Christian, and Critical
  traditions.

### Tooling
- `fetch_transcripts.py` using yt-dlp with `--download-archive` for
  incremental, idempotent transcript downloads.
- `generate_arguments_sql.py` for converting found-comments into seedable SQL
  (Option A schema: metadata folded into `content`).

### Known limitations
- Chronological order is wired in the data and the query, but the reader UI
  still treats it as book-level (each book read whole, books reordered by
  first-chapter chrono seq) instead of real verse-level interleaving. Proper
  chunked rendering is in progress.
- Apocrypha-only translations (KJV / DRA / WEB) show a few empty
  "Song of the Three" / mislabelled "Esther (Greek)" entries when scrolled
  into the deuterocanon — cosmetic only.
- BaseX dba web UI is not available in the `basex/basexhttp` Docker image; only
  `/rest` works on port 8984.
