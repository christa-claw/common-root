// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.CommentReference;
import org.religioustext.app.model.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Seeds translator footnotes from {@code transcripts/notes-<edition>.json}
 * ledgers into the database as comments owned by a locked system account named
 * for the edition — the 1912 Lutherbibel's marginal glosses become comments
 * authored by "Lutherbibel 1912", exactly as a channel's arguments are comments
 * authored by the channel. One object kind, different ownership (the
 * {@code cmt_} id scheme's own rule).
 *
 * <p>The ledgers are written by {@code scripts/bibles/02_convert_osis_flat.py
 * --notes} — the same pass that strips {@code <note>} subtrees out of the
 * scripture text captures them here, with deterministic {@code cmt_} ids from
 * {@code comment_id.mint_note_id} that the merge below keys on.
 *
 * <p>Merge semantics are DataSeeder's V14 policy verbatim (via
 * {@link DataSeeder#decideSeedAction}): human edits outrank the ledger,
 * tombstones stay dead, un-edited rows refresh in place, orphans are swept.
 * Both the merge and the sweep are scoped to THIS file's ledger via
 * {@code seed_ledger} (V16), so this seeder and DataSeeder can never delete
 * each other's rows — the exact failure the column exists to prevent.
 *
 * <p>Unlike arguments, notes are edition-BOUND: each reference carries the
 * edition's corpus source id, and the read side renders a {@code notes-%}
 * ledger's comment only under that edition's column. A 1912 gloss under a KJV
 * column would be apparatus without its text; the V5 edition-independence rule
 * is for comments ABOUT scripture, not comments OF an edition.
 */
@Service
public class NotesSeeder {

    private static final Logger log = LoggerFactory.getLogger(NotesSeeder.class);

    /** Never-deliverable domain for edition accounts. Deliberately NOT the
     *  channels domain: a channels-domain placeholder means "claimable once a
     *  real address is known" (DataSeeder.isPlaceholderEmail), and an edition
     *  is never claimable — nobody answers outreach for 1912. */
    static final String EDITION_EMAIL_DOMAIN = "editions.common-root.org";

    /** Everything seeding needs to know about one edition's notes ledger:
     *  the account/display name readers see, the language-appropriate word for
     *  "note" in the attribution header, and the corpus source id that
     *  edition-binds each reference on the read side. */
    record NotesEdition(String displayName, String noteLabel, String sourceId) {}

    /** The editions with a notes ledger. A ledger whose {@code metadata.edition}
     *  is not here is SKIPPED loudly rather than seeded with guessed labels —
     *  add the entry (display name, note word, corpus source id) and reboot. */
    static final Map<String, NotesEdition> EDITIONS = Map.of(
        // Source id is the corpus's own quirky "bible-lut1912-1912" (see
        // CHANGELOG 0.6.x: derived from bible-sources.yml before ids were
        // explicit; renaming means an XQuery update, so it stays).
        "LUT1912", new NotesEdition("Lutherbibel 1912", "Anmerkung", "bible-lut1912-1912"));

    @PersistenceContext
    private EntityManager em;

    @Value("${religioustext.transcripts-dir:transcripts}")
    private String transcriptsDir;

    /** After DataSeeder (100) — not for correctness (the V16 scoping makes the
     *  two independent) but so the startup log reads in ledger order — and
     *  before DevTestDataSeeder (200), which assumes all seeding is done. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(150)
    @Transactional
    public void seed() {
        final File dir = locateTranscriptsDir();
        if (dir == null) {
            log.info("No transcripts dir under '{}' — no note ledgers to seed.", transcriptsDir);
            return;
        }
        final File[] ledgers = dir.listFiles(
            (d, name) -> name.startsWith("notes-") && name.endsWith(".json"));
        if (ledgers == null || ledgers.length == 0) return;
        java.util.Arrays.sort(ledgers);   // deterministic order across boots
        for (final File ledger : ledgers) seedLedger(ledger);
    }

    /** Seed ONE ledger file, scoped entirely to its own seed_ledger value. */
    private void seedLedger(final File aFile) {
        // "notes-lut1912.json" -> ledger 'notes-lut1912' (the seed_ledger value)
        final String ledgerName = aFile.getName()
            .substring(0, aFile.getName().length() - ".json".length());
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final Map<String, Object> root = mapper.readValue(
                aFile, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            @SuppressWarnings("unchecked")
            final Map<String, Object> metadata = (Map<String, Object>) root.get("metadata");
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> notes = (List<Map<String, Object>>) root.get("notes");
            final String abbr = metadata == null ? null : (String) metadata.get("edition");
            final NotesEdition edition = abbr == null ? null : EDITIONS.get(abbr);
            if (edition == null || notes == null) {
                log.warn("Skipping {}: edition '{}' has no NotesSeeder.EDITIONS entry "
                    + "(or the file has no notes array). Nothing seeded from it.",
                    aFile.getName(), abbr);
                return;
            }
            log.info("Seeding {} note(s) for {} from {}",
                notes.size(), abbr, aFile.getAbsolutePath());

            // The V14 merge, scoped to THIS ledger (V16): rows of other ledgers —
            // DataSeeder's arguments above all — are invisible here, so the orphan
            // sweep at the end can only ever delete this ledger's own rows.
            final java.util.Map<String, Comment> existing = new java.util.HashMap<>();
            for (final Comment c : em.createQuery(
                    "SELECT c FROM Comment c JOIN FETCH c.user "
                        + "WHERE c.user.system = true AND c.seedLedger = :ledger",
                    Comment.class).setParameter("ledger", ledgerName).getResultList()) {
                if (c.getPublicId() != null) existing.put(c.getPublicId(), c);
            }
            final java.util.Set<String> tombstoned = new java.util.HashSet<>(em.createQuery(
                "SELECT t.publicId FROM CommentTombstone t", String.class).getResultList());

            final User owner = resolveEditionUser(edition);

            int seeded = 0, updated = 0, preserved = 0, skippedTombstoned = 0, skipped = 0;
            final java.util.Set<String> seenIds = new java.util.HashSet<>();
            for (final Map<String, Object> note : notes) {
                final String publicId = (String) note.get("id");
                final String text = (String) note.get("text");
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> verseRefs =
                    (List<Map<String, Object>>) note.get("verse_refs");
                // The converter never writes an id-less, text-less or ref-less
                // note; a hand-edited ledger might. Same rule as arguments:
                // an unanchorable or unmatchable entry is skipped, not seeded.
                if (publicId == null || publicId.isBlank()
                        || text == null || text.isBlank()
                        || verseRefs == null || verseRefs.isEmpty()
                        || !seenIds.add(publicId)) {
                    skipped++;
                    continue;
                }

                final String content = noteContent(edition.displayName(),
                    edition.noteLabel(), (String) note.get("anchor"), text);

                final Comment existingRow = existing.remove(publicId);
                switch (DataSeeder.decideSeedAction(existingRow != null,
                        existingRow != null && existingRow.isLocallyEdited(),
                        tombstoned.contains(publicId))) {
                    case SKIP_TOMBSTONED -> skippedTombstoned++;
                    case PRESERVE -> preserved++;   // a human edited it — the ledger loses
                    case UPDATE -> {
                        existingRow.setContent(content);
                        existingRow.getReferences().clear();   // orphanRemoval deletes on flush
                        addNoteRefs(existingRow, verseRefs, edition.sourceId());
                        existingRow.makePublic();   // no external refs -> approved
                        updated++;
                    }
                    case INSERT -> {
                        final Comment comment = new Comment();
                        comment.setUser(owner);
                        comment.setContent(content);
                        comment.setPublicId(publicId);      // stable permalink id (merge key)
                        comment.setSeedLedger(ledgerName);  // scopes future merges/sweeps (V16)
                        em.persist(comment);
                        addNoteRefs(comment, verseRefs, edition.sourceId());
                        comment.makePublic();   // no external refs -> approved
                        seeded++;
                    }
                }
            }

            // Ledger orphans: same policy as arguments — a human edit outranks
            // the ledger even in death; everything else follows the file.
            int orphansDeleted = 0, orphansPreserved = 0;
            for (final Comment orphan : existing.values()) {
                if (orphan.isLocallyEdited()) { orphansPreserved++; continue; }
                em.remove(orphan);
                orphansDeleted++;
            }

            log.info("{}: {} new, {} updated, {} preserved (edited), {} tombstoned, "
                + "{} orphans deleted, {} edited orphans kept, {} skipped.",
                ledgerName, seeded, updated, preserved, skippedTombstoned,
                orphansDeleted, orphansPreserved, skipped);

        } catch (final Exception e) {
            log.error("Failed to seed {}: {}", aFile.getName(), e.getMessage(), e);
        }
    }

    /**
     * One note's comment body. The header is self-attribution — edition-bound
     * display means the note only ever renders under its own edition's column,
     * but the comment also travels (permalinks, search), where the anchor line
     * alone would read as a bare gloss with no owner.
     *
     * <pre>[Lutherbibel 1912 — Anmerkung]
     *
     * »Das dritte Wasser heißt Hiddekel« — Tigris</pre>
     *
     * An anchor-less note (possible in a hand-edited ledger) drops the
     * quotation and keeps the header + text.
     */
    static String noteContent(final String aDisplayName, final String aNoteLabel,
                              final String anAnchor, final String aText) {
        final String header = String.format("[%s — %s]", aDisplayName, aNoteLabel);
        final String body = (anAnchor == null || anAnchor.isBlank())
            ? aText.strip()
            : String.format("»%s« — %s", anAnchor.strip(), aText.strip());
        return header + "\n\n" + body;
    }

    /** A note's verse reference(s): USFM code + chapter + verse, carrying the
     *  edition's corpus source id — which is what edition-binds the note on the
     *  read side (a 'notes-%' comment renders only where its sourceId matches
     *  the column). Notes never carry external references, so makePublic()
     *  auto-approves them. */
    private void addNoteRefs(final Comment aComment, final List<Map<String, Object>> theVerseRefs,
                             final String aSourceId) {
        int position = 0;
        for (final Map<String, Object> vr : theVerseRefs) {
            final String code = (String) vr.get("code");
            final int chapter = toInt(vr.get("chapter"));
            final int verse   = toInt(vr.get("verse"));
            if (code == null || code.isBlank() || chapter == 0 || verse == 0) continue;
            final CommentReference ref = CommentReference.internal(
                aComment, position++, aSourceId, code, chapter, verse);
            aComment.getReferences().add(ref);
            em.persist(ref);
        }
    }

    /** Find-or-create the locked system account that owns an edition's notes.
     *  Keyed on displayName like channel accounts, but on the editions email
     *  domain and permanently unclaimable — there is no outreach address for a
     *  1912 translation committee, and channels.properties never upgrades an
     *  editions-domain address (DataSeeder.isPlaceholderEmail is channels-only). */
    private User resolveEditionUser(final NotesEdition anEdition) {
        final List<User> found = em.createQuery(
                "SELECT u FROM User u WHERE u.system = true AND u.displayName = :name",
                User.class)
            .setParameter("name", anEdition.displayName()).setMaxResults(1).getResultList();
        if (!found.isEmpty()) return found.get(0);
        final User u = new User();
        u.setEmail(editionSlug(anEdition.displayName()) + "@" + EDITION_EMAIL_DOMAIN);
        u.setDisplayName(anEdition.displayName());
        u.setPasswordHash(DataSeeder.LOCKED_HASH);   // no login, no claim — ever
        u.setVerified(false);
        u.setSystem(true);
        em.persist(u);
        return u;
    }

    /** Stable, email-safe slug of an edition display name ("Lutherbibel 1912"
     *  -> "lutherbibel-1912"). No hash suffix: edition names come from the
     *  EDITIONS map, which is code — two colliding entries would be a bug
     *  there, not an input to survive. */
    static String editionSlug(final String aName) {
        final String base = aName.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return base.isBlank() ? "edition" : base;
    }

    /** The transcripts dir, tried from the working directory and its parent —
     *  the same two-step DataSeeder uses (prod runs in /app, dev in app/). */
    private File locateTranscriptsDir() {
        final File direct = new File(transcriptsDir);
        if (direct.isDirectory()) return direct;
        if (!direct.isAbsolute()) {
            final File fromParent = new File("..", transcriptsDir);
            if (fromParent.isDirectory()) return fromParent;
        }
        return null;
    }

    private int toInt(final Object aValue) {
        if (aValue instanceof Integer i) return i;
        if (aValue instanceof Number n) return n.intValue();
        return 0;
    }
}
