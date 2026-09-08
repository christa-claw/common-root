# docker/basex-content/Dockerfile
#
# Bakes the curated corpus (Bible, Qur'an, Hadith, and the seed comment catalog)
# into a portable BaseX image. The data is COPIED into the image — there is NO
# runtime volume — so the corpus travels with the image and a
# `docker compose pull && up` on the server swaps in newly-ingested texts.
#
# The build context is this directory. It expects ./data to be a snapshot of the
# local BaseX data directory, produced by scripts/publish/build_basex_image.sh.

FROM basex/basexhttp:latest

# Copy the corpus in, owned by the BaseX runtime user (uid 1984) so BaseX can
# open the databases read-write (it needs to write locks/state into the data
# dir). docker cp extracts files as root; without this chown BaseX can list the
# DB but every query fails with a permission error and the reader looks empty.
COPY --chown=basex:basex data /srv/basex/data
