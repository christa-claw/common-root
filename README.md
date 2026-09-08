# Common Root?

A Vaadin-based application for reading, studying and comparing the scriptures of
the Abrahamic faiths — the Bible, Quran, Hadith and commentaries — across
translations, languages and historical orderings.

> **Common Root?** — read these texts side by side and weigh for yourself what
> they share and where they diverge.

## Prerequisites

- Docker Desktop (running)
- IntelliJ IDEA Community with JDK 21 (Eclipse Temurin)

That is all. Java, Maven, MySQL and BaseX all run in Docker.

## First-time setup

### 1. Start the databases

```bash
cd docker
docker-compose up -d
```

Wait ~60 seconds for both containers to become healthy:

```bash
docker-compose ps
```

Both should show `healthy`.

### 2. Verify BaseX

Open http://localhost:8984 in your browser and log in with the BaseX
credentials from `docker/docker-compose.yml`. You should see the BaseX
dashboard. The scripture texts live in the `religioustext` database, one
XML document per translation (e.g. `bible-niv-2011.xml`).

### 3. Open the project in IntelliJ

File -> Open -> select the religious-texts folder (the one containing this README).
IntelliJ will detect the Maven multi-module project automatically.
Let it import and download dependencies (first run takes a few minutes).

### 4. Run the app

Run `org.religioustext.app.ReligiousTextsApp`
Open http://localhost:8090 — the landing page is the About / Help page; the
reader itself is at http://localhost:8090/reader

## Project structure

```
religious-texts/                 (repo slug — app is "Common Root?")
  schema/          XSD schema + sample XML files
  docker/          docker-compose.yml, MySQL config, init SQL
  app/             Vaadin reader application (port 8090)
  ingestion/       Ingestion pipeline (port 8091)
  transcripts/     YouTube transcript pipeline + generated SQL
  docs/            Overview & technical documentation
  README.md
```

> **Note on naming:** the user-facing application is called **Common Root?**.
> The repository slug (`religious-texts`), Java package (`org.religioustext`),
> BaseX database (`religioustext`) and Docker container (`religioustext-mysql`)
> retain the original internal identifier and are intentionally left unchanged.

## Ingesting the Bible

Run `org.religioustext.ingestion.IngestionApp`, then trigger ingestion via the
REST endpoint:

```bash
curl -X POST http://localhost:8091/ingest/KJV     # a single translation
curl -X POST http://localhost:8091/ingest/all     # everything configured
```

## Stopping the databases

```bash
cd docker
docker-compose down
```

Full reset including all data:

```bash
docker-compose down -v
```

## Support

Common Root? is free and open. If it's useful to you, you can support its
development via GitHub Sponsors:

https://github.com/sponsors/christa-claw

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
