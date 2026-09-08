package org.religioustext.app.service;

import org.religioustext.app.model.VerseRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Content attestation (docs/api-spec.md §3.7): an HMAC over a canonical form
 * of a passage plus a server-held secret, so text taken from the API can later
 * be verified as served by this site, unmodified, for a given corpus version —
 * by recomputation alone, with no archived corpora.
 *
 * <h2>Canonical form, version {@code v1}</h2>
 * Never the wire bytes: a consumer who re-serialises JSON, converts format or
 * gains a trailing newline must not break verification. Lines joined by
 * {@code \n}, UTF-8, no trailing newline:
 * <pre>
 *   canon:v1
 *   corpus:{corpus hash label}
 *   minted:{ISO-8601 UTC instant, whole seconds}   ← when this tag was made
 *   app:{application version that made it}
 *   text:{edition token, e.g. kjv}    ← per passage, so a bundle may mix editions
 *   ref:{normalised link-format reference}
 *   {verse number}\t{verse text}      ← one per verse, document order
 *   text:{next edition}
 *   ref:{next reference}
 *   …
 * </pre>
 * One tag covers <em>everything</em> a response carried — a bundle verifies as
 * one object, exactly as the consumer holds it. The mint time and build are
 * signed, not merely printed: an unsigned "served on" is a claim anyone can
 * edit, a signed one is a timestamp — "this text was served by this site, at
 * this moment, from this corpus, by this build". The price is that the same
 * passage minted twice carries two tags, which verification never needed to
 * be otherwise. The edition travels with each
 * passage rather than in the header because the bundle worth attesting is
 * often the same verse across several translations ("when did this reading
 * appear?"), and that comparison must be provable as one thing.
 * {@code verse text} is the reader's display text — {@link VerseRef#clean}
 * (pilcrows, section marks, appended footnotes and stray whitespace removed) —
 * then Unicode NFC. That is the text a JSON consumer already holds; an XML
 * consumer applies the same documented cleaning before verifying. The recipe
 * outlives the algorithm: change {@code v1} and every existing tag is orphaned,
 * so it does not change.
 *
 * <h2>Keys</h2>
 * The tag carries a key id ({@code kid}); secrets rotate by giving the new one
 * a new id and keeping the retired ones verify-only, so a tag minted in March
 * still verifies in November. With no secret configured (local dev) a random
 * one is generated per boot and logged as such — tags then verify only until
 * the next restart, which is the honest behaviour for a secret nobody set.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Service
public class AttestationService {

    private static final Logger log = LoggerFactory.getLogger(AttestationService.class);

    public static final String ALG   = "HMAC-SHA256";
    public static final String CANON = "v1";

    private final String kid;
    private final String corpusHash;
    private final String appVersion;
    private final Clock  clock;
    private final Map<String, byte[]> secrets = new LinkedHashMap<>();   // kid -> secret, current first

    public AttestationService(
             @Value("${api.attest.kid:k1}") final String aKid
            , @Value("${api.attest.secret:}") final String aSecret
            , @Value("${api.attest.retired:}") final String aRetired
            , @Value("${api.corpus.hash:dev}") final String aCorpusHash
            , @Value("${religioustext.version:dev}") final String anAppVersion
            , final Clock aClock) {
        this.kid        = aKid;
        this.corpusHash = aCorpusHash;
        this.appVersion = anAppVersion;
        this.clock      = aClock;
        byte[] current;
        if (aSecret == null || aSecret.isBlank()) {
            current = new byte[32];
            new SecureRandom().nextBytes(current);
            log.warn("api.attest.secret is not set — using a per-boot random secret; "
                   + "attestation tags will not survive a restart. Set API_ATTEST_SECRET in prod.");
        } else {
            current = aSecret.getBytes(StandardCharsets.UTF_8);
        }
        secrets.put(aKid, current);
        if (aRetired != null && !aRetired.isBlank()) {
            for (final String pair : aRetired.split(",")) {
                final int colon = pair.indexOf(':');
                if (colon > 0) {
                    secrets.put(pair.substring(0, colon).trim(),
                                pair.substring(colon + 1).trim().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    }

    /**
     * A minted or presented attestation.
     *
     * @param alg    the MAC algorithm
     * @param kid    which secret signed it
     * @param canon  the canonical-form version
     * @param corpus the corpus version label the text came from
     * @param minted when the tag was made, ISO-8601 UTC, whole seconds
     * @param app    the application version that made it
     * @param tag    the MAC, lowercase hex
     */
    public record Attestation(String alg, String kid, String canon, String corpus,
                              String minted, String app, String tag) {

        /**
         * The attestation as an XML processing instruction, placed before the
         * served element so a saved XML file is self-validating while the
         * element itself stays a pure {@code religious-text.xsd} subtree —
         * a validator ignores processing instructions.
         *
         * @return {@code <?common-root-attestation alg="…" kid="…" … tag="…"?>}
         */
        public String processingInstruction() {
            return "<?common-root-attestation alg=\"" + alg + "\" kid=\"" + kid + "\" canon=\"" + canon
                 + "\" corpus=\"" + corpus + "\" minted=\"" + minted + "\" app=\"" + app
                 + "\" tag=\"" + tag + "\"?>";
        }

        /** The single-header wire form for plain-text and XML responses. */
        public String headerValue() {
            return "alg=" + alg + "; kid=" + kid + "; canon=" + canon + "; corpus=" + corpus
                 + "; minted=" + minted + "; app=" + app + "; tag=" + tag;
        }
    }

    /** One verse as the canonical form sees it. */
    public record Line(int number, String text) { }

    /** One passage of a bundle: its edition, normalised reference and cleaned lines. */
    public record Passage(String text, String ref, List<Line> lines) { }

    public String currentKid()  { return kid; }
    public String corpusHash()  { return corpusHash; }

    /**
     * Mint the attestation for a passage served now, under the current key and
     * corpus label.
     *
     * @param anEditionId the edition token ({@code kjv}) — the permanent address, not the document id
     * @param aRef        the normalised reference
     * @param theVerses   the verses served, in order
     * @return the attestation to attach
     */
    public Attestation mint(final String anEditionId, final String aRef, final List<VerseRef> theVerses) {
        return mintBundle(List.of(passage(anEditionId, aRef, theVerses)));
    }

    /**
     * Mint one attestation over a whole bundle of passages — the scattered
     * reference case: one tag for the combination of all the texts served,
     * in served order.
     *
     * @param thePassages  each passage's edition, reference and verses, in served order
     * @return the attestation to attach once, at the top of the response
     */
    public Attestation mintBundle(final List<Passage> thePassages) {
        final String minted = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        return new Attestation(ALG, kid, CANON, corpusHash, minted, appVersion,
            tag(secrets.get(kid), canonical(corpusHash, minted, appVersion, thePassages)));
    }

    /**
     * A served passage reduced to what the canonical form sees: reference plus
     * cleaned lines.
     *
     * @param anEditionId the edition document id
     * @param aRef        the normalised reference
     * @param theVerses   the verses served
     * @return the passage
     */
    public static Passage passage(final String anEditionId, final String aRef, final List<VerseRef> theVerses) {
        return new Passage(anEditionId, aRef, theVerses.stream()
            .map(v -> new Line(v.getVerseNumber(), VerseRef.clean(v.getContent()))).toList());
    }

    /** Single-passage mint over already-cleaned lines. Package-visible for the tests. */
    Attestation mintLines(final String anEditionId, final String aRef, final List<Line> theLines) {
        return mintBundle(List.of(new Passage(anEditionId, aRef, theLines)));
    }

    /**
     * Verify a presented bundle. Fails closed on everything — unknown key id,
     * unknown canon version, any field changed — and says nothing about why:
     * a reason would be an oracle for forging.
     *
     * @param aPresented  the attestation fields as presented
     * @param anEditionId the edition the caller claims
     * @param aRef        the reference the caller claims
     * @param theLines    the verse texts the caller holds (cleaned by the caller as documented)
     * @return {@code true} only if this site minted exactly this
     */
    public boolean verify(final Attestation aPresented, final String anEditionId,
                          final String aRef, final List<Line> theLines) {
        return verifyBundle(aPresented, List.of(new Passage(anEditionId, aRef, theLines)));
    }

    /**
     * Verify a presented bundle of one or more passages, in the order presented.
     *
     * @param aPresented  the attestation fields as presented
     * @param thePassages the passages the caller holds, each with its edition
     * @return {@code true} only if this site minted exactly this
     */
    public boolean verifyBundle(final Attestation aPresented, final List<Passage> thePassages) {
        if (aPresented == null || !ALG.equals(aPresented.alg()) || !CANON.equals(aPresented.canon())) return false;
        final byte[] secret = secrets.get(aPresented.kid());
        if (secret == null || aPresented.tag() == null || thePassages == null || thePassages.isEmpty()) return false;
        final String expected = tag(secret, canonical(aPresented.corpus(), aPresented.minted(),
                                                      aPresented.app(), thePassages));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                                     aPresented.tag().trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * The canonical form, version {@code v1}. Package-visible so the tests can
     * pin it with golden vectors.
     *
     * @param anEditionId the edition document id
     * @param aCorpus     the corpus hash label
     * @param aRef        the normalised reference
     * @param theLines    verse number and (already cleaned) text, in order
     * @return the canonical string, NFC
     */
    static String canonical(final String anEditionId, final String aCorpus, final String aMinted,
                            final String anApp, final String aRef, final List<Line> theLines) {
        return canonical(aCorpus, aMinted, anApp, List.of(new Passage(anEditionId, aRef, theLines)));
    }

    /**
     * The canonical form over a bundle: header, then each passage's
     * {@code ref:} line and verse lines, in order.
     *
     * @param aCorpus     the corpus hash label
     * @param aMinted     the mint instant, ISO-8601 UTC
     * @param anApp       the application version
     * @param thePassages the passages, each with its edition, in served order
     * @return the canonical string, NFC
     */
    static String canonical(final String aCorpus, final String aMinted, final String anApp,
                            final List<Passage> thePassages) {
        final StringBuilder sb = new StringBuilder();
        sb.append("canon:").append(CANON).append('\n');
        sb.append("corpus:").append(nz(aCorpus)).append('\n');
        sb.append("minted:").append(nz(aMinted)).append('\n');
        sb.append("app:").append(nz(anApp));
        for (final Passage p : thePassages) {
            sb.append('\n').append("text:").append(nz(p.text()));
            sb.append('\n').append("ref:").append(nz(p.ref()));
            for (final Line line : p.lines()) {
                sb.append('\n').append(line.number()).append('\t')
                  .append(Normalizer.normalize(nz(line.text()), Normalizer.Form.NFC));
            }
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
    }

    static String tag(final byte[] aSecret, final String aCanonical) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(aSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(aCanonical.getBytes(StandardCharsets.UTF_8)));
        } catch (final java.security.GeneralSecurityException e) {
            throw new IllegalStateException("JVM without HmacSHA256", e);   // cannot happen
        }
    }

    private static String nz(final String aValue) { return aValue == null ? "" : aValue; }
}
