# Transcript Pipeline

This directory contains YouTube transcripts used to pre-populate
verse-specific arguments and commentary in the Religious Texts Platform.

---

## How Transcripts Are Fetched

Transcripts are downloaded using [yt-dlp](https://github.com/yt-dlp/yt-dlp) via `fetch_transcripts.py`.

### Install

```bash
brew install yt-dlp
pip3 install webvtt-py anthropic
```

### Fetch all channels

```bash
python3 scripts/channels/01_fetch_transcripts.py
```

### Fetch a single channel

```bash
python3 scripts/channels/01_fetch_transcripts.py --channel godlogic
```

### Fetch specific content types only

```bash
python3 scripts/channels/01_fetch_transcripts.py --channel shamounian --types videos
python3 scripts/channels/01_fetch_transcripts.py --types streams  # all channels, streams only
```

### Preview without downloading

```bash
python3 scripts/channels/01_fetch_transcripts.py --dry-run
```

### Add a new channel

1. Add entry to `channels.properties` (channel key, URL, optional types)
2. Add tradition mapping to `CHANNEL_TRADITION` in `extract_arguments.py`
3. Run `fetch_transcripts.py --channel newkey`
4. Run `extract_arguments.py` — resumes automatically, skips already-processed files

### Processed URL tracking

`transcripts/downloaded.txt` is a yt-dlp video archive file that tracks individual video IDs.
Re-running `fetch_transcripts.py` will scan the full channel but skip any video already in the archive.
This means **new videos published after the last run will be downloaded automatically** on the next run.

---

## How Arguments Are Extracted

```bash
export ANTHROPIC_API_KEY=your_key_here
python3 scripts/channels/02_extract_arguments.py
```

The script (`extract_arguments.py` in project root):
- Iterates all channel folders in `transcripts/`
- Skips obvious non-argument videos (reactions, news, live streams)
- Extracts verse references from filenames where possible
- Converts each `.vtt` to clean plain text (deduplicates rolling captions)
- Sends to Claude API to extract: verse refs, argument summary, argument type
- Saves incrementally to `transcripts/arguments.json`
- **Resumable** — already-processed files are skipped on re-run

Output schema per entry in `arguments.json`:
```json
{
  "source_file": "Shamounian Explains/20240902_Does Jesus say...",
  "channel": "Shamounian Explains",
  "tradition": "Christian",
  "argument_type": "contextual",
  "argument_summary": "Shamoun argues Luke 19:27 is part of a parable...",
  "verse_refs": [
    { "type": "bible", "code": "LUK", "chapter": 19, "verse": 27 }
  ],
  "useful": true
}
```

`argument_type` values:
- `contextual` — argues the verse is misunderstood without context
- `translation` — argument depends on a specific translation choice
- `prophecy` — OT prophecy / NT fulfilment argument
- `theological` — doctrinal argument (Trinity, prophethood, atonement etc.)
- `comparative` — cross-tradition comparison (Bible vs Quran)
- `historical` — historical/manuscript argument

---

## Channels

| Channel | Tradition | Handle |
|---|---|---|
| Dr Zakir Naik | Islamic | @Drzakirchannel |
| Ali Dawah | Islamic | @AliDawah |
| DUS Dawah | Islamic | @DUSDawah |
| Mohammed Hijab | Islamic | @MohammedHijab |
| Apologetics Roadshow | Christian | @apologeticsroadshow |
| GodLogic Apologetics | Christian | @GodLogicApologetics |
| Hatun Tash DCCI Ministries | Christian | @HatunTashDCCIMinistries |
| Shamounian Explains | Christian | @ShamounianExplains |
| JihadWatch | Critical | @JihadWatchVideo |
| Raymond Ibrahim | Critical | @RaymondIbrahim-HW |

---

## Notes

- Transcripts are **not committed to git** (see `.gitignore`)
- They are reference material only — the extracted `arguments.json` is what matters
- `arguments.json` **should** be committed once generated
- Re-running `extract_arguments.py` after adding a new channel only processes new files
- API.Bible rate limit does not apply here — this uses the Anthropic API directly
- Estimated cost: ~$0.002 per video at Claude Sonnet pricing
