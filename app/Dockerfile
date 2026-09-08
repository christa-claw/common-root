# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy parent pom first (cache layer)
COPY pom.xml /build/../pom.xml
COPY app/pom.xml pom.xml

# Download dependencies before copying source (cache layer)
RUN mvn dependency:go-offline -q || true

# Copy source and build production JAR (Vaadin production mode)
# docs/ must land at /docs (= ../docs from /build): the app pom packs its PDFs
# into the jar at META-INF/resources/docs for the About page's download links.
# `mvn package` also runs the maven-javadoc-plugin (prepare-package execution),
# generating the API reference into the jar at META-INF/resources/docs/api —
# served at /docs/api and linked from the About page. No extra step needed here.
COPY app/src ./src
COPY docs /docs
RUN mvn package -Pproduction -DskipTests -q

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser

COPY --from=build /build/target/*.jar app.jar

# Seed source for DataSeeder: at startup it reads transcripts/arguments.json
# relative to the working dir (/app) and rebuilds the system-user comments from
# it. Baking it in means the comment catalogue travels with the app image and
# refreshes on every deploy (DataSeeder re-seeds deterministically). Without
# this the deployed app finds no file and seeds zero comments.
COPY --chown=appuser:appgroup transcripts/arguments.json /app/transcripts/arguments.json

# Translator-note ledgers: NotesSeeder scans transcripts/notes-*.json at startup
# (e.g. notes-lut1912.json, the 1912 Lutherbibel's 85 footnotes) and seeds them
# as edition-bound comments, with the same V14 merge as arguments. Same
# travels-with-the-image rationale; without this line the prod container has no
# ledgers and NotesSeeder silently seeds zero notes.
COPY --chown=appuser:appgroup transcripts/notes-*.json /app/transcripts/

# Channel registry: DataSeeder reads channels.properties at startup for each
# channel's real outreach email (the .email lines) to make its account claimable
# via password-reset. Same rationale as arguments.json — it travels with the
# image and refreshes on deploy. Missing => channels fall back to unclaimable
# placeholders (the seed still runs).
COPY --chown=appuser:appgroup channels.properties /app/channels.properties

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]
