# Common Root?

_Study the texts that shape our world._

<!-- AUTOGEN:meta -->
| Field | Value |
|---|---|
| Application | Common Root? |
| Version | 0.8.11 |
| Generated | 2026-09-22 |
<!-- /AUTOGEN:meta -->

## What it is

Common Root? is a free, open reader for exploring the scriptures of the Abrahamic
faiths — Bible translations, the Quran, Hadith, and commentaries — side by side,
in any language, at any depth.

The name is an invitation. Read these texts alongside one another and weigh for
yourself what they share and where they diverge.

The platform is independent. It is not affiliated with any religious organisation
or publisher. Reading and study are always free; an optional free account adds
the ability to leave private comments and notes.

## Who it is for

Scholars, students, and curious readers alike. Whether you are comparing how a
single verse is rendered across translations, tracing a prophecy across both
testaments, or reading a book as continuous prose the way its earliest audience
did, the reader is built to get out of your way.

## How reading works

The reader is built around **columns**. Each column shows one text — a Bible
translation, the Quran, a commentary, or your own notes. You can open as many
columns as you like and mix traditions freely. Columns scroll together by
default so the same passage stays aligned, and any column can be unlinked to
browse independently.

### Display modes

A core idea of the platform is that the divisions we take for granted —
chapters, verses, section headings — are editorial layers added long after the
texts were written. Each column can be switched between modes that reveal or
hide these layers:

- **Scriptio Continua** — continuous text in the ancient manuscript style, with
  no chapter or verse divisions. Chapters within a book run together; a labelled
  rule marks each book boundary.
- **Chapters (1227)** — the chapter system added by Stephen Langton around 1227.
- **Verses (1551)** — verse numbers added by Robert Estienne in 1551.
- **Titles** — modern editorial section headings, where the translation supplies
  them.

### Reading order

Each column can also present its books in different orders: the traditional
**canonical** order, or a scholarly **chronological** order that interleaves
passages across books — so the prophets and psalms can be read in their
historical setting rather than in isolation.

### Reading the Quran

A Quran column shows the Arabic text, and you can choose a translation to show
beneath each ayah. The Arabic stays primary, with the translation paired under it
in the same scrolling column so the two never drift apart. The translation picker
offers only the translations of that Quran.

### Shareable links

The reader's full state — each column's text, the passage it is open at, its
display mode and reading order, any translation overlay, and whether the columns
scroll together — is captured in the page URL. Copy the link to return to the
exact same view later, or share it with someone else. References are edition- and
language-independent, so a link created in one translation opens correctly in
another.

## Texts currently available

<!-- AUTOGEN:translations -->
_BaseX query skipped (install `requests` to enable live ingestion stats)._
<!-- /AUTOGEN:translations -->

More translations and traditions — including the Quran, Hadith collections, and
additional Bible versions in many languages — are being added continuously.

## Chapter audio

Public-domain editions are gradually being given spoken audio: one mp3 per
chapter, plus an index of per-verse millisecond offsets so the reading line can
follow along and a single verse can be linked to. Coverage as it stands:

<!-- AUTOGEN:audio -->
_No audio manifest at `audio/index.json`; coverage table not refreshed. Set `COMMONROOT_AUDIO_INDEX` to point at it._
<!-- /AUTOGEN:audio -->

This is a best-effort push to get public-domain scripture into audio at all, not
a published audio Bible. The reading is synthetic — neural text-to-speech, one
voice per edition, no human narrator and no studio pass — and it is generated a
few chapters a night inside a free character allowance, so a Bible-sized edition
takes months to fill in. Only public-domain texts are read; editions whose
licence forbids derivative works are excluded on purpose.

The point is to cover texts that have nothing. Finnish 1933 and the Delitzsch
Hebrew New Testament have no audio edition worth the name, while English is
served abundantly elsewhere — which is why English is deliberately last in the
queue rather than first.

### What the machine reading gets wrong

Worth knowing before listening, and worth reporting when you hear it:

- **Proper names.** Hebrew and Greek names carried into Finnish are frequently
  mispronounced. The verse text goes to the engine as plain running text with no
  pronunciation lexicon, so nothing corrects a name it guesses wrong.
- **Archaic spelling.** The 1776 Finnish predates modern orthography and the
  engine's text normaliser was not built for it. Numbers, abbreviations and
  old spellings are read however it reads them.
- **One voice throughout.** Narration, dialogue and quoted speech share the same
  register; the audio never marks who is speaking.
- **Verse timings can be imperfect.** Chapters are synthesised as continuous
  prose on purpose, so phrasing carries across verse divisions instead of
  stopping at every one. The offsets come from marks the engine reports as it
  passes each verse and are normally exact — but a chapter where it reports
  fewer marks than the chapter has verses is still published, with the shortfall
  only noted in the run log, so a few chapters may highlight imprecisely.
- **Seams in long chapters.** Anything past roughly 7,500 characters is
  synthesised in parts and rejoined. Joins are placed at verse boundaries, where
  a pause is natural, but one can occasionally be heard.

Nothing already generated is re-generated automatically — a chapter on disk is a
chapter already paid for — so correcting a bad reading is a deliberate act:
delete that chapter and let the next night's run rebuild it.

## Commentary and arguments

Beyond the primary texts, the platform ingests and indexes argument material
about specific verses, drawn from a range of perspectives across the Abrahamic
traditions and from critical analysis. These are linked to the verses they
discuss, so a reader studying a contested passage can see how it is argued from
different angles.

Sources are organised by channel and perspective:

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

## Notes and comments of your own

Reading never requires an account. With a free account, every verse becomes
writable: click a verse number to open the comment editor — your text plus the
passages it cites (add as many as you like, e.g. a theme traced across
several books) and an optional video link. Comments are private until you
choose to publish, and your own annotations — drafts included — appear under
every verse they cite as you read. Channels that claim their account can
correct and curate their own imported arguments directly.

## Support

Common Root? is free and always will be. If it is useful to you, your support
helps cover hosting, licensed translation access, and the time to add new texts
and features.

Buy Me a Coffee: https://buymeacoffee.com/christaclaw
