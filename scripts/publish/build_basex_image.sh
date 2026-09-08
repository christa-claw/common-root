#!/usr/bin/env bash
set -euo pipefail
#
# build_basex_image.sh — snapshot the local BaseX corpus, bake it into an
# amd64 image, and push to GHCR. Run from the repo root AFTER your local
# ingestion is tested. Requires: docker login ghcr.io (token with
# write:packages — not the old PAT from .git/config).
#
# Expects docker/basex-content/Dockerfile to exist and the local BaseX
# container to be running (container name religioustext-basex).

IMAGE=ghcr.io/christa-claw/religious-texts/basex:latest
CTX=docker/basex-content

# 1. Snapshot the live corpus out of the running local BaseX container's volume.
#    (Named-volume data is not captured by `docker commit`, so we copy it out
#    and COPY it into the image explicitly.)
echo "Snapshotting corpus from religioustext-basex ..."
rm -rf "$CTX/data"
docker cp religioustext-basex:/srv/basex/data "$CTX/data"

# 2. Cross-build for the server's architecture and push in one step.
#    --platform matters: your M4 is arm64, the Linode is amd64. The base
#    basex/basexhttp image is multi-arch, so amd64 is available.
echo "Building and pushing $IMAGE (linux/amd64) ..."
docker buildx build --platform linux/amd64 \
  -t "$IMAGE" -f "$CTX/Dockerfile" --push "$CTX"

echo "Pushed $IMAGE"
echo "Tip: add docker/basex-content/data/ to .gitignore so the snapshot isn't committed."
