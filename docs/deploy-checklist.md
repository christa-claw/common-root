# Deploy checklist

Three data planes reach prod by **different** paths. A plain app redeploy only
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

## PENDING (as of 2026-07-22)
- [ ] **YTC Turkish Bible** — BaseX image already built + pushed to GHCR. Pull to
  prod on the NEXT app push or next arguments refresh (Christa's call — batched,
  not a standalone restart). Confirm YTC is selectable in the picker afterward;
  if not, the app-side source registration isn't deployed yet.
- [ ] YTC licence is **CC BY-ND 4.0** — confirm side-by-side display is
  non-derivative use before publishing widely.
