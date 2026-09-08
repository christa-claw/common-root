// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.religioustext.app.model.user.Comment;
import org.religioustext.app.model.user.CommentReference;
import org.religioustext.app.model.user.User;
import org.religioustext.app.util.TypedId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Seeds pre-populated arguments from transcripts/arguments.json into the database.
 * Runs at startup and RE-SEEDS deterministically: all system-user comments are
 * replaced from the file, so enrichment passes over arguments.json (video links,
 * timecodes, corrections) land in the DB on the next start. Safe because system
 * comments are never user-edited and nothing references their ids. If the file
 * is missing or unparseable, the existing rows are left untouched.
 */
@Service
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private static final String SYSTEM_USER_ID =
        "usr-00000000-0000-7000-8000-000000000001";

    /** Placeholder email domain for channel accounts we have no real outreach
     *  address for yet — unique, but not a deliverable mailbox, so such channels
     *  stay unclaimable until their real address is set (see resolveChannelUser). */
    private static final String CHANNEL_EMAIL_DOMAIN = "channels.common-root.org";

    /** Obviously-not-bcrypt placeholder password. BCrypt.matches() returns false
     *  for it (never throws), so a channel account can't be logged into — the
     *  ONLY way in is the password-reset flow (reset = "claim your channel").
     *  Package-visible: NotesSeeder locks its edition accounts the same way
     *  (those are never claimable at all — no reset email will ever go out). */
    static final String LOCKED_HASH = "LOCKED-NO-LOGIN";

    // source_id null = translation-independent reference
    private static final String BIBLE_SOURCE_ID = null;

    /** This seeder's ledger (V16): the merge and the orphan sweep touch ONLY rows
     *  stamped with it, so other seeders' rows (e.g. NotesSeeder's 'notes-lut1912')
     *  are invisible here and can never be swept as orphans. */
    static final String LEDGER = "arguments";

    @PersistenceContext
    private EntityManager em;

    // Ensures the SYSTEM default ACL exists (the fallback for unclaimed channels' comments).
    // Org member groups + org ACLs are NOT created here — they're provisioned on CLAIM.
    @Autowired
    private AclService aclService;

    @Value("${religioustext.transcripts-dir:transcripts}")
    private String transcriptsDir;

    /** Runs once the application is fully up. NOTE: deliberately an
     *  ApplicationReadyEvent listener, NOT @PostConstruct — init methods are
     *  invoked directly on the bean instance, bypassing the transaction proxy,
     *  so @Transactional silently does nothing there and the JPQL bulk deletes
     *  die with TransactionRequiredException. Event listeners are dispatched
     *  through the proxy, so the transaction is real. */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)   // before DevTestDataSeeder (@Order 200), which claims a channel this creates
    @Transactional
    public void seed() {
        final File jsonFile = locateArgumentsJson();
        if (jsonFile == null) {
            log.warn("No arguments.json under '{}' (tried from the working directory and its"
                + " parent; cwd={}). Keeping existing seed.",
                transcriptsDir, new File(".").getAbsolutePath());
            return;
        }
        log.info("Seeding arguments from {}", jsonFile.getAbsolutePath());

        try {
            final ObjectMapper mapper = new ObjectMapper();
            final List<Map<String, Object>> entries = mapper.readValue(
                jsonFile, new TypeReference<>() {});

            // MERGE, don't replace (V14 — "channel corrections survive restarts"): load the
            // existing seed keyed by stable public_id. Rows a human edited are PRESERVED
            // verbatim; un-edited rows are UPDATED in place (their acl_id survives);
            // tombstoned ids are never resurrected. Legacy rows predating the id scheme
            // can't be matched, so they alone are dropped and rebuilt.
            // Scoped to THIS seeder's ledger (V16) — before the seed_ledger column,
            // this query loaded every system-account comment, which would have made
            // the orphan sweep below delete NotesSeeder's rows on every boot.
            final java.util.Map<String, Comment> existing = new java.util.HashMap<>();
            int legacyDropped = 0;
            for (final Comment c : em.createQuery(
                    "SELECT c FROM Comment c JOIN FETCH c.user "
                        + "WHERE c.user.system = true AND c.seedLedger = :ledger",
                    Comment.class).setParameter("ledger", LEDGER).getResultList()) {
                if (c.getPublicId() != null && !c.getPublicId().isBlank()) {
                    existing.put(c.getPublicId(), c);
                } else {
                    em.remove(c);   // pre-id-scheme row — unmatchable
                    legacyDropped++;
                }
            }
            final java.util.Set<String> tombstoned = new java.util.HashSet<>(em.createQuery(
                    "SELECT t.publicId FROM CommentTombstone t", String.class).getResultList());
            if (!existing.isEmpty() || legacyDropped > 0)
                log.info("Merging into existing seed ({} rows, {} tombstones, {} legacy dropped).",
                    existing.size(), tombstoned.size(), legacyDropped);

            // Channel accounts are created lazily as channels are first seen and
            // cached for the run; only their comments were deleted above — the
            // accounts themselves persist across restarts. channelEmails maps a
            // channel name to its real outreach address (channels.properties), if
            // known — the difference between a claimable and a placeholder account.
            final java.util.Map<String, User> channelUsers = new java.util.HashMap<>();
            final java.util.Map<String, String> channelEmails = loadChannelEmails();

            // The system default ACL — the final fallback for any object with no ACL of its
            // own and no org default (docs/access-control.md §4). Idempotent.
            aclService.findOrCreateSystemDefaultAcl();

            int seeded = 0;
            int updated = 0;
            int preserved = 0;
            int skippedTombstoned = 0;
            int skippedNoId = 0;
            int skippedNoSource = 0;
            int skippedNoRefs = 0;
            int skippedDupId = 0;
            final java.util.Set<String> seenPublicIds = new java.util.HashSet<>();
            for (final Map<String, Object> entry : entries) {
                if (!Boolean.TRUE.equals(entry.get("useful"))) continue;

                // Auto-generated comments MUST carry a source (video) link — rule
                // 2026-07-01: a Platform-owned comment with no external reference
                // is invalid (user-authored comments are exempt; they don't come
                // through this seeder). Entries the enrichment pass could not
                // resolve a video for are skipped rather than seeded link-less.
                final String videoUrl = (String) entry.get("video_url");
                if (videoUrl == null || videoUrl.isBlank()) { skippedNoSource++; continue; }

                // Ref-less arguments can't anchor to any verse in the reader —
                // they'd seed as invisible rows. Skip them (78% of the ledger is
                // ref-less, see the 2026-07-06 finding; the extractor's ref
                // capture is a separate open item).
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> verseRefs =
                    (List<Map<String, Object>>) entry.get("verse_refs");
                if (verseRefs == null || verseRefs.isEmpty()) { skippedNoRefs++; continue; }

                // Deterministic permalink id minted by the extractor / backfill
                // (comment_id.py). The MERGE keys on it, so an id-less entry cannot be
                // matched across boots (it would duplicate every restart) — skipped; the
                // backfill stamped every ref-bearing entry. Duplicate = byte-identical
                // argument; seeded once.
                final String publicId = (String) entry.get("id");
                if (publicId == null || publicId.isBlank()) {
                    skippedNoId++;
                    continue;
                }
                if (!seenPublicIds.add(publicId)) {
                    skippedDupId++;
                    continue;
                }

                final String summary = (String) entry.get("argument_summary");
                final String channel  = (String) entry.get("channel");
                final String tradition = (String) entry.get("tradition");
                final String argType  = (String) entry.get("argument_type");
                if (summary == null || summary.isBlank()) continue;

                // Build comment content with attribution header
                final String content = String.format(
                    "[%s — %s — %s]\n\n%s",
                    channel, tradition, argType, summary);

                final Comment existingRow = existing.remove(publicId);
                switch (decideSeedAction(existingRow != null,
                        existingRow != null && existingRow.isLocallyEdited(),
                        tombstoned.contains(publicId))) {
                    case SKIP_TOMBSTONED -> skippedTombstoned++;
                    case PRESERVE -> preserved++;   // a human edited it — the ledger loses
                    case UPDATE -> {
                        // Un-edited: refresh from the ledger IN PLACE — the row (and with it
                        // its acl_id, permalinks, and identity) survives; refs are rebuilt.
                        existingRow.setContent(content);
                        existingRow.getReferences().clear();   // orphanRemoval deletes on flush
                        addLedgerRefs(existingRow, verseRefs, videoUrl, channel, entry);
                        publish(existingRow);
                        updated++;
                    }
                    case INSERT -> {
                        final Comment comment = new Comment();
                        comment.setUser(resolveChannelUser(channel, channelUsers, channelEmails));
                        comment.setContent(content);
                        comment.setPublicId(publicId);   // stable permalink id (merge key)
                        comment.setSeedLedger(LEDGER);   // scopes future merges/sweeps (V16)
                        em.persist(comment);
                        addLedgerRefs(comment, verseRefs, videoUrl, channel, entry);
                        publish(comment);
                        seeded++;
                    }
                }
            }

            // Rows in the DB but no longer in the ledger: drop them UNLESS a human edited
            // them — an edited orphan (e.g. a re-extraction dropped the argument after the
            // channel fixed it) is kept; their correction outranks the ledger.
            int orphansDeleted = 0;
            int orphansPreserved = 0;
            for (final Comment orphan : existing.values()) {
                if (orphan.isLocallyEdited()) { orphansPreserved++; continue; }
                em.remove(orphan);
                orphansDeleted++;
            }

            log.info("Seed merge: {} new, {} updated, {} preserved (edited), {} tombstoned, "
                + "{} orphans deleted, {} edited orphans kept "
                + "({} skipped: no id; {} no source link; {} no verse refs; {} duplicate id).",
                seeded, updated, preserved, skippedTombstoned,
                orphansDeleted, orphansPreserved,
                skippedNoId, skippedNoSource, skippedNoRefs, skippedDupId);

        } catch (final Exception e) {
            log.error("Failed to seed arguments: {}", e.getMessage(), e);
        }
    }

    /**
     * Publish a seeded argument: public AND approved, regardless of reference order.
     *
     * {@link Comment#makePublic()} sends anything carrying an external link to {@code pending}
     * — an anti-spam rule for USER-authored comments. Every seeded argument carries its source
     * video link, so it would always land pending and be invisible; seeded rows come from our
     * own ledger and are trusted, so the approval is explicit here. (This used to work only by
     * accident: the insert path happened to call makePublic() before attaching refs. The V14
     * update path called it with the OLD refs still attached and silently made all 3.6k
     * comments pending — invisible sitewide.)
     */
    private void publish(final Comment aComment) {
        aComment.makePublic();
        aComment.approve();
    }

    /** What the seed merge does with one ledger entry (V14 edit-aware reseeding). */
    enum SeedAction { INSERT, UPDATE, PRESERVE, SKIP_TOMBSTONED }

    /**
     * The merge decision for one ledger entry — pure, so the policy is testable:
     * a tombstone always wins (a deleted comment stays dead); a missing row is inserted;
     * an existing row is preserved verbatim when a human edited it, else updated in place.
     *
     * @param anExists       a row with this public id already exists
     * @param aLocallyEdited that row carries a human edit
     * @param aTombstoned    the id was deliberately deleted through the editor
     * @return the {@link SeedAction} to take
     */
    static SeedAction decideSeedAction(final boolean anExists, final boolean aLocallyEdited,
                                       final boolean aTombstoned) {
        if (aTombstoned) return SeedAction.SKIP_TOMBSTONED;
        if (!anExists) return SeedAction.INSERT;
        return aLocallyEdited ? SeedAction.PRESERVE : SeedAction.UPDATE;
    }

    /** Build a ledger entry's references onto a comment: the verse refs (Bible/LDS code +
     *  chapter + verse, or Qur'an surah/ayah — edition-independent, null source id), each
     *  with its timecoded video link when resolved, plus the external watch link. Shared by
     *  the INSERT and UPDATE merge paths. */
    private void addLedgerRefs(final Comment aComment, final List<Map<String, Object>> theVerseRefs,
                               final String aVideoUrl, final String aChannel,
                               final Map<String, Object> anEntry) {
        int position = 0;
        for (final Map<String, Object> vr : theVerseRefs) {
            final String type = (String) vr.get("type");
            final CommentReference ref;
            if ("bible".equals(type) || "lds".equals(type)) {
                final String code    = (String) vr.get("code");
                final int    chapter = toInt(vr.get("chapter"));
                final int    verse   = toInt(vr.get("verse"));
                if (code == null || chapter == 0 || verse == 0) continue;
                ref = CommentReference.internal(aComment, position++, null, code, chapter, verse);
            } else if ("quran".equals(type)) {
                final int surah = toInt(vr.get("surah"));
                final int ayah  = toInt(vr.get("ayah"));
                if (surah == 0 || ayah == 0) continue;
                ref = CommentReference.internal(aComment, position++,
                    null, String.valueOf(surah), 1, ayah);
            } else {
                continue;
            }
            final String videoLink = (String) vr.get("video_link");
            if (videoLink != null && !videoLink.isBlank()) ref.setVideoUrl(videoLink);
            aComment.getReferences().add(ref);
            em.persist(ref);
        }
        final CommentReference watch = CommentReference.external(
            aComment, position, aVideoUrl,
            truncate(aChannel == null ? "YouTube" : aChannel, 255),
            truncate(titleFrom((String) anEntry.get("source_file")), 1000));
        aComment.getReferences().add(watch);
        em.persist(watch);
    }

    /** Find-or-create the system (channel) account that owns a channel's
     *  comments. Created lazily on first sighting and cached per run; the
     *  account persists across restarts (only its comments are re-seeded).
     *
     *  Access is claim-only: the account ships with a locked (non-bcrypt)
     *  password, so the sole way a real person gets in is the password-reset
     *  flow — reset IS "claim your channel", and whoever completes it becomes the
     *  founding member. That only works once the account's email is the channel's
     *  REAL outreach address (reset delivers there); until then it carries an
     *  undeliverable placeholder and stays unclaimable. */
    private User resolveChannelUser(final String aChannel,
                                    final java.util.Map<String, User> aCache,
                                    final java.util.Map<String, String> theEmails) {
        final String name = (aChannel == null || aChannel.isBlank()) ? "Unattributed" : aChannel.strip();
        final String realEmail = theEmails.get(name);   // null if we have no address yet
        final boolean hasEmail = realEmail != null && !realEmail.isBlank();
        // ONE ACCOUNT PER ORGANISATION, not per channel. Several channels may list the
        // same outreach address (a ministry running more than one channel); they share a
        // single claimable account, which then owns ALL of their comments — one claim,
        // one login, edit rights over every channel they run.
        // Display is unaffected: a seeded argument's voice comes from the external
        // reference's label (the channel name, set in addReferences), never from this
        // account's displayName — CommentQueryService.authorOf returns null for system
        // users. So merged channels keep their own labels, filters and mute handles.
        // Cache key is prefixed so an address can never collide with a channel name.
        final String cacheKey = hasEmail
            ? "email:" + realEmail.toLowerCase(java.util.Locale.ROOT)
            : "name:" + name;
        return aCache.computeIfAbsent(cacheKey, k -> {
            // 1. An account already exists FOR THIS CHANNEL (keyed on displayName). It owns
            //    this channel's existing comments, so it always wins: never re-point a
            //    channel at another row on a boot — that would orphan its comments.
            final List<User> found = em.createQuery(
                    "SELECT u FROM User u WHERE u.system = true AND u.displayName = :name", User.class)
                .setParameter("name", name).setMaxResults(1).getResultList();
            if (!found.isEmpty()) {
                final User existing = found.get(0);
                // A real email newly added to channels.properties upgrades the existing
                // placeholder account so it becomes claimable — but never touch a CLAIMED
                // account (real bcrypt hash) or a real email that's already set, and never
                // take an address another account already holds: users.email is UNIQUE, so
                // that would fail the seed and with it the boot.
                if (hasEmail
                        && LOCKED_HASH.equals(existing.getPasswordHash())
                        && isPlaceholderEmail(existing.getEmail())
                        && !realEmail.equalsIgnoreCase(existing.getEmail())) {
                    if (emailHeldByAnother(realEmail, existing.getId())) {
                        log.warn("Channel '{}' cannot take outreach address {} — another account "
                               + "already holds it. Left unclaimable. If they are the same "
                               + "organisation, merge the two accounts deliberately (moving the "
                               + "comments) rather than letting a boot decide.", name, realEmail);
                    } else {
                        existing.setEmail(realEmail);
                        log.info("Channel '{}' is now claimable (outreach email set).", name);
                    }
                }
                return existing;
            }
            // 2. No account for this channel yet, but its organisation already has one at
            //    the same outreach address — join it, so one claim covers both channels.
            if (hasEmail) {
                final List<User> byEmail = em.createQuery(
                        "SELECT u FROM User u WHERE u.system = true AND LOWER(u.email) = :email",
                        User.class)
                    .setParameter("email", realEmail.toLowerCase(java.util.Locale.ROOT))
                    .setMaxResults(1).getResultList();
                if (!byEmail.isEmpty()) {
                    log.info("Channel '{}' joins existing organisation account '{}' ({}) — "
                           + "one account now owns both channels' comments.",
                             name, byEmail.get(0).getDisplayName(), realEmail);
                    return byEmail.get(0);
                }
            }
            // 3. First sighting of this organisation: a new account.
            final User u = new User();
            // Real outreach address when known (claimable); otherwise a unique,
            // undeliverable placeholder (unclaimable until an address is added).
            u.setEmail(hasEmail ? realEmail : channelSlug(name) + "@" + CHANNEL_EMAIL_DOMAIN);
            u.setDisplayName(name);
            u.setPasswordHash(LOCKED_HASH);     // no login; claim only via password reset
            u.setVerified(false);               // unclaimed -> not verified
            u.setSystem(true);
            em.persist(u);
            return u;
        });
        // NOTE: NO member group / org ACL is created at ingest — unclaimed channels' comments
        // inherit the SYSTEM default ACL (deferred provisioning minimises ACL count). The
        // org member group + org default ACL are created only when the channel is CLAIMED
        // (AccessService.claimChannel). See docs/access-control.md §4.
    }

    /** True if some OTHER account already holds this address. {@code users.email} is
     *  UNIQUE, so assigning a duplicate throws and fails the whole seed — i.e. the boot.
     *  A hand edit to channels.properties must never be able to do that. */
    private boolean emailHeldByAnother(final String anEmail, final String anExcludedUserId) {
        return !em.createQuery(
                "SELECT u.id FROM User u WHERE LOWER(u.email) = :email AND u.id <> :id", String.class)
            .setParameter("email", anEmail.toLowerCase(java.util.Locale.ROOT))
            .setParameter("id", anExcludedUserId)
            .setMaxResults(1).getResultList().isEmpty();
    }

    /** True if the email is an auto-generated placeholder (undeliverable) — i.e.
     *  the channel has no real outreach address yet, so it isn't claimable. */
    private static boolean isPlaceholderEmail(final String anEmail) {
        return anEmail != null && anEmail.endsWith("@" + CHANNEL_EMAIL_DOMAIN);
    }

    /** Stable, email/URL-safe slug of a channel name. A short hash of the full
     *  name is appended so two names that slug alike (punctuation-only diffs)
     *  still get distinct, unique emails rather than merging into one account. */
    private static String channelSlug(final String aName) {
        final String base = aName.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        final String hash = Integer.toHexString(aName.hashCode() & 0xffff);
        return (base.isBlank() ? "channel" : base) + "-" + hash;
    }

    /** channel display-name (.folder) -> real outreach email (.email), parsed
     *  from channels.properties. A channel with an email becomes claimable
     *  (reset-to-claim delivers there); one without falls back to an unclaimable
     *  placeholder. Empty when the file is absent — safe: all channels are then
     *  simply unclaimable, the seed still runs. */
    private java.util.Map<String, String> loadChannelEmails() {
        final File file = locateChannelsProperties();
        if (file == null) {
            log.info("channels.properties not found — channel accounts get placeholder "
                + "emails (unclaimable until real addresses are added).");
            return java.util.Map.of();
        }
        final java.util.Properties props = new java.util.Properties();
        try (java.io.Reader r = java.nio.file.Files.newBufferedReader(
                file.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            props.load(r);
        } catch (final Exception e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
            return java.util.Map.of();
        }
        final java.util.Map<String, String> byFolder = new java.util.HashMap<>();
        for (final String key : props.stringPropertyNames()) {
            if (!key.endsWith(".folder")) continue;
            final String base   = key.substring(0, key.length() - ".folder".length());
            final String folder = props.getProperty(key);
            final String email  = props.getProperty(base + ".email");
            if (folder != null && email != null && !email.isBlank())
                byFolder.put(folder.strip(), email.strip());
        }
        if (!byFolder.isEmpty())
            log.info("Loaded {} channel outreach email(s) from {}", byFolder.size(), file.getName());
        return byFolder;
    }

    /** Locate channels.properties: the working dir (prod: /app, baked in by the
     *  Dockerfile) or its parent (dev: mvn runs in the app module, the file is at
     *  the repo root one level up). */
    private File locateChannelsProperties() {
        final File direct = new File("channels.properties");
        if (direct.exists()) return direct;
        final File parent = new File("..", "channels.properties");
        return parent.exists() ? parent : null;
    }

    /** Resolve arguments.json under the configured transcripts dir. A relative
     *  dir is tried from the working directory AND from its parent — mvn
     *  spring-boot:run uses the app module as the working directory while the
     *  transcripts live at the repo root, one level up. */
    private File locateArgumentsJson() {
        final File direct = new File(transcriptsDir, "arguments.json");
        if (direct.exists()) return direct;
        if (!new File(transcriptsDir).isAbsolute()) {
            final File fromParent = new File(".." + File.separator + transcriptsDir, "arguments.json");
            if (fromParent.exists()) return fromParent;
        }
        return null;
    }

    private int toInt(final Object aValue) {
        if (aValue instanceof Integer i) return i;
        if (aValue instanceof Number n) return n.intValue();
        return 0;
    }

    /** Human title from a transcript source_file: basename minus the date
     *  prefix, any [videoid] token, and the subtitle extension. */
    private static String titleFrom(final String aSourceFile) {
        if (aSourceFile == null) return "";
        String t = aSourceFile.substring(aSourceFile.lastIndexOf('/') + 1);
        t = t.replaceFirst("^\\d{8}_", "")
             .replaceAll("\\s*\\[[A-Za-z0-9_-]{11}\\]", "")
             .replaceFirst("(\\.[a-z]{2}([-.][A-Za-z]+)*)?\\.vtt$", "");
        return t.trim();
    }

    private static String truncate(final String aString, final int aMax) {
        if (aString == null) return "";
        return aString.length() <= aMax ? aString : aString.substring(0, aMax);
    }
}
