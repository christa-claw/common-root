# Deploy checklist

Four data planes reach prod by **different** paths. A plain app redeploy only
updates the app — it does NOT carry scripture or arguments. Check each at deploy.

## 1. App (code)
- GH build on the release tag → deploy the app image (green PROD chip, version bump).

## 2. Scripture (BaseX) — separate image
- Scripture lives in BaseX, shipped as its own image, NOT in the app jar.
- Rebuild + push after local ingestion: `bash scripts/publish/build_basex_image.sh`
  (needs local `religioustext-basex` running + `docker login ghcr.io`).
- On prod: `docker pull …/basex:latest` then `docker compose up -d basex`
  (`:latest` keeps the old image until the container is recreated).
- **A new translation needs BOTH images together**: BaseX (the text) AND the app
  (its source registration + About card). Ship one alone and it either shows in
  the picker with no text, or has text nobody can select.

## 3. Arguments (comments) — arguments.json → MySQL via the seeder
- The reader seeds comments from `transcripts/arguments.json` at boot (V14 merge:
  new args inserted, human edits preserved, tombstones stay deleted).
- Getting refreshed args to prod = commit `arguments.json` + redeploy the app
  (or however arguments.json reaches the prod container).

## 4. Chapter audio (mp3s) — rsync from the Mac mini, NOT a deploy
- Audio is never in the app image or the BaseX image. Prod serves it off disk:
  `./audio` on the host, bind-mounted read-only into caddy (`/audio/*`) and into
  the app (`/srv/audio`, which reads only `index.json`).
- It ships in step 7 of `scripts/automation/nightly_tts.sh`: an additive
  `rsync -az --ignore-existing` to `$PROD_AUDIO_TARGET`, run at the end of every
  nightly generation. Nothing about an app redeploy moves audio, and nothing about
  shipping audio needs a redeploy — the app re-reads the manifest on mtime change,
  within a minute.
- So the check at deploy time is simply: did the last nightly run log a successful
  push, or a `WARN: rsync failed`? A failed push is a night of audio still sitting
  on the Mac.
- **Known gap — `index.json` and `--ignore-existing`.** That flag is right for the
  mp3s (immutable, append-only, cheap to re-run) but wrong for the manifest, which
  is rewritten every run and is the only thing telling the app which chapters
  exist. Once a manifest is on the server it is never replaced, so chapters keep
  arriving as files while the reader stops noticing them. Until the script sends
  the manifest separately, a manual
  `rsync -az "$AUDIO_SOURCE_DIR/index.json" "$PROD_AUDIO_TARGET/"` (where
  `$AUDIO_SOURCE_DIR` is wherever this machine keeps the audio tree — see
  `COMMONROOT_AUDIO_INDEX` in `docs/build_docs.py`)
  is what makes newly shipped chapters visible.

## PENDING (as of 2026-07-22)
- [ ] **YTC Turkish Bible** — BaseX image already built + pushed to GHCR. Pull to
  prod on the NEXT app push or next arguments refresh (maintainer's call — batched,
  not a standalone restart). Confirm YTC is selectable in the picker afterward;
  if not, the app-side source registration isn't deployed yet.
- [ ] YTC licence is **CC BY-ND 4.0** — confirm side-by-side display is
  non-derivative use before publishing widely.
