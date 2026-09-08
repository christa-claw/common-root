## About this repository

This is the public mirror of the Common Root? codebase. It is exported from the
private working repository one commit per release, so its history is the
sequence of releases (see `CHANGELOG.md`) rather than the day-to-day work.

**Included:** the application (`app/`), the ingestion service (`ingestion/`),
the corpus schema and pipelines (`schema/`, `scripts/`), the container
definitions (`docker/`), the API specification and reference documents
(`docs/`), and the edition information pages (`app/src/main/resources/i18n/editions/`).

**Not included:** the scripture corpus itself, the transcript-derived argument
ledger (`transcripts/arguments.json`), translator-note ledgers and the channel
registry (`channels.properties`). The copies of those files in this repository
are empty placeholders so that the Docker image builds; a build from this tree
runs with no seeded comments and an empty corpus until you ingest texts of your
own (see `docs/`). Licensed editions served on common-root.org are not
redistributed here or through the API.

Some comments and Javadoc cite design notes that live only in the private
repository (`docs/access-control.md`, `docs/search.md`, `docs/groups.md`,
`docs/api-design.md`); the decisions they record are reflected in the code,
the migrations and `CHANGELOG.md`.

**Licence:** Apache License 2.0 — see `LICENSE` and `NOTICE`.
