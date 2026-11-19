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

## PENDING (as of 2026-10-06)
- [x] ~~Prod's compose flag~~ — done 2026-11-19 18:50 (backup
  `docker-compose.prod.yml.bak-java4shell-20261119`); prod's CPU has all four
  affected features, so it was exposed until then. Flag verified `false`.
- [ ] **Java4Shell (CVE-2026-114938), 2026-11-19.** Until the next app image (whose
  Dockerfile ENTRYPOINT carries the flag) is live, prod's own compose file needs
  `JAVA_TOOL_OPTIONS=-Xmx3g -XX:-UseBASE64Intrinsics`, edited in place on the box,
  then `docker compose -f docker-compose.prod.yml up -d app`. Check it took:
  `docker exec religioustext-app java -XX:+PrintFlagsFinal -version | grep UseBASE64Intrinsics`
  reads `false`. Later: when `eclipse-temurin:21-jre-jammy` reaches 21.0.13.1+,
  rebuild and remove the flag from both places.
- [x] ~~YTC Turkish Bible — pull to prod on the next app push.~~ Done: confirmed
  live and selectable with text in the 0.9.3 deploy (2026-10-06).
- [ ] YTC licence is **CC BY-ND 4.0** — confirm side-by-side display is
  non-derivative use before publishing widely.
