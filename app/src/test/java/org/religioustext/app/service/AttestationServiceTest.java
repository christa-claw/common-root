package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.AttestationService.Attestation;
import org.religioustext.app.service.AttestationService.Line;
import org.religioustext.app.service.AttestationService.Passage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The canonical form and the tag, pinned with golden vectors and an
 * independent HMAC (docs/api-test-cases.md A1, A2, A7, A8, A10). Never the
 * production code path checking itself.
 */
class AttestationServiceTest {

    private static final String SECRET = "test-secret-do-not-use";

    private static final String MINTED = "2026-09-06T14:02:11Z";
    private static final String APP    = "0.8.0-test";

    private final AttestationService service = new AttestationService("k1", SECRET, "k0:old-secret",
        "corpus-abc", APP, Clock.fixed(Instant.parse(MINTED), ZoneOffset.UTC));

    private static String independentHmac(final String aSecret, final String aCanonical) throws Exception {
        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(aSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(aCanonical.getBytes(StandardCharsets.UTF_8)));
    }

    // A1 — the canonical form is a fixed recipe
    @Test
    void canonicalFormIsTheDocumentedRecipe() {
        final String canon = AttestationService.canonical("bible-kjv", "corpus-abc", MINTED, APP, "JHN.1.1-2",
            List.of(new Line(1, "In the beginning was the Word"), new Line(2, "The same was in the beginning")));
        assertThat(canon).isEqualTo(
            "canon:v1\n"
          + "corpus:corpus-abc\n"
          + "minted:" + MINTED + "\n"
          + "app:" + APP + "\n"
          + "text:bible-kjv\n"
          + "ref:JHN.1.1-2\n"
          + "1\tIn the beginning was the Word\n"
          + "2\tThe same was in the beginning");
    }

    // A1 — NFC: a decomposed "é" and a precomposed "é" canonicalise identically;
    // an RTL verse passes through untouched
    @Test
    void canonicalFormIsNfcAndDirectionNeutral() {
        final String decomposed = "e\u0301";   // e + combining acute
        final String composed   = "\u00e9";
        assertThat(AttestationService.canonical("t", "c", MINTED, APP, "r", List.of(new Line(1, decomposed))))
            .isEqualTo(AttestationService.canonical("t", "c", MINTED, APP, "r", List.of(new Line(1, composed))));

        final String arabic = "ٱللَّهُ لَآ إِلَٰهَ إِلَّا هُوَ";
        assertThat(AttestationService.canonical("q", "c", MINTED, APP, "Q.2.255", List.of(new Line(255, arabic))))
            .endsWith("255\t" + Normalizer.normalize(arabic, Normalizer.Form.NFC));
    }

    // A2 — the tag is HMAC-SHA256 over the canonical form, computed independently here
    @Test
    void tagIsHmacSha256OverTheCanonicalForm() throws Exception {
        final List<Line> lines = List.of(new Line(16, "For God so loved the world"));
        final Attestation a = service.mintLines("bible-kjv", "JHN.3.16", lines);
        final String canon = AttestationService.canonical("bible-kjv", "corpus-abc", MINTED, APP, "JHN.3.16", lines);
        assertThat(a.tag()).isEqualTo(independentHmac(SECRET, canon));
        assertThat(a.alg()).isEqualTo("HMAC-SHA256");
        assertThat(a.kid()).isEqualTo("k1");
        assertThat(a.canon()).isEqualTo("v1");
    }

    // A6/A7/A8 — a round trip verifies; any single field mutated does not
    @Test
    void verifyPassesUnmodifiedAndFailsOnAnyChange() {
        final List<Line> lines = List.of(new Line(16, "For God so loved the world"));
        final Attestation a = service.mintLines("bible-kjv", "JHN.3.16", lines);

        assertThat(service.verify(a, "bible-kjv", "JHN.3.16", lines)).isTrue();

        // text tampered
        assertThat(service.verify(a, "bible-kjv", "JHN.3.16",
            List.of(new Line(16, "For God so loved the whole world")))).isFalse();
        // verse number tampered
        assertThat(service.verify(a, "bible-kjv", "JHN.3.16", List.of(new Line(17, "For God so loved the world")))).isFalse();
        // edition / ref / corpus tampered
        assertThat(service.verify(a, "bible-web", "JHN.3.16", lines)).isFalse();
        assertThat(service.verify(a, "bible-kjv", "JHN.3.17", lines)).isFalse();
        assertThat(service.verify(new Attestation(a.alg(), a.kid(), a.canon(), "corpus-xyz", a.minted(), a.app(), a.tag()),
            "bible-kjv", "JHN.3.16", lines)).isFalse();
        // tag tampered / wrong canon / wrong alg
        assertThat(service.verify(new Attestation(a.alg(), a.kid(), a.canon(), a.corpus(), a.minted(), a.app(), "00" + a.tag().substring(2)),
            "bible-kjv", "JHN.3.16", lines)).isFalse();
        assertThat(service.verify(new Attestation(a.alg(), a.kid(), "v2", a.corpus(), a.minted(), a.app(), a.tag()),
            "bible-kjv", "JHN.3.16", lines)).isFalse();
        assertThat(service.verify(new Attestation("HMAC-MD5", a.kid(), a.canon(), a.corpus(), a.minted(), a.app(), a.tag()),
            "bible-kjv", "JHN.3.16", lines)).isFalse();
    }

    // A9 — a past corpus label still verifies: the tag is recomputed from the
    // presented label, no archive needed
    @Test
    void pastCorpusVersionsVerifyByRecomputation() throws Exception {
        final List<Line> lines = List.of(new Line(1, "x"));
        final String oldCanon = AttestationService.canonical("t", "corpus-OLD", MINTED, APP, "GEN.1.1", lines);
        final Attestation minted = new Attestation("HMAC-SHA256", "k1", "v1", "corpus-OLD", MINTED, APP,
            independentHmac(SECRET, oldCanon));
        assertThat(service.verify(minted, "t", "GEN.1.1", lines)).isTrue();
    }

    // A10 — a retired key still verifies; an unknown kid never does
    @Test
    void retiredKeysVerifyUnknownKidsDoNot() throws Exception {
        final List<Line> lines = List.of(new Line(1, "x"));
        final String canon = AttestationService.canonical("t", "corpus-abc", MINTED, APP, "GEN.1.1", lines);

        final Attestation retired = new Attestation("HMAC-SHA256", "k0", "v1", "corpus-abc", MINTED, APP,
            independentHmac("old-secret", canon));
        assertThat(service.verify(retired, "t", "GEN.1.1", lines)).isTrue();

        final Attestation unknown = new Attestation("HMAC-SHA256", "k9", "v1", "corpus-abc", MINTED, APP,
            independentHmac("old-secret", canon));
        assertThat(service.verify(unknown, "t", "GEN.1.1", lines)).isFalse();
    }

    // A bundle: one tag over the combination of all the texts, in served order,
    // each passage carrying its own edition — the same verse across
    // translations is one provable object. Reordering, dropping a passage, or
    // relabelling an edition breaks it; a single passage canonicalises exactly
    // as the single-passage recipe.
    @Test
    void bundleIsOneTagOverAllPassagesInOrderAcrossEditions() {
        final Passage kjv = new Passage("bible-kjv", "JHN.3.16", List.of(new Line(16, "For God so loved the world")));
        final Passage web = new Passage("bible-web", "JHN.3.16", List.of(new Line(16, "For God so loved the world")));

        assertThat(AttestationService.canonical("corpus-abc", MINTED, APP, List.of(kjv, web))).isEqualTo(
            "canon:v1\n"
          + "corpus:corpus-abc\n"
          + "minted:" + MINTED + "\n"
          + "app:" + APP + "\n"
          + "text:bible-kjv\n"
          + "ref:JHN.3.16\n"
          + "16\tFor God so loved the world\n"
          + "text:bible-web\n"
          + "ref:JHN.3.16\n"
          + "16\tFor God so loved the world");
        assertThat(AttestationService.canonical("corpus-abc", MINTED, APP, List.of(kjv)))
            .isEqualTo(AttestationService.canonical("bible-kjv", "corpus-abc", MINTED, APP, "JHN.3.16", kjv.lines()));

        final Attestation tag = service.mintBundle(List.of(kjv, web));
        assertThat(service.verifyBundle(tag, List.of(kjv, web))).isTrue();
        assertThat(service.verifyBundle(tag, List.of(web, kjv))).isFalse();   // order matters
        assertThat(service.verifyBundle(tag, List.of(kjv))).isFalse();        // a passage dropped
        assertThat(service.verifyBundle(tag, List.of(                          // edition relabelled
            kjv, new Passage("bible-niv", "JHN.3.16", web.lines())))).isFalse();
        assertThat(service.verifyBundle(tag, List.of())).isFalse();
    }

    @Test
    void tagCaseIsForgiven() {
        final List<Line> lines = List.of(new Line(1, "x"));
        final Attestation a = service.mintLines("t", "GEN.1.1", lines);
        final Attestation upper = new Attestation(a.alg(), a.kid(), a.canon(), a.corpus(), a.minted(), a.app(), a.tag().toUpperCase());
        assertThat(service.verify(upper, "t", "GEN.1.1", lines)).isTrue();
    }

    // The mint time and build are signed: changing either orphans the tag
    @Test
    void mintTimeAndBuildAreSigned() {
        final List<Line> lines = List.of(new Line(1, "x"));
        final Attestation a = service.mintLines("kjv", "GEN.1.1", lines);
        assertThat(a.minted()).isEqualTo(MINTED);
        assertThat(a.app()).isEqualTo(APP);
        assertThat(service.verify(a, "kjv", "GEN.1.1", lines)).isTrue();
        assertThat(service.verify(new Attestation(a.alg(), a.kid(), a.canon(), a.corpus(),
            "2026-09-07T00:00:00Z", a.app(), a.tag()), "kjv", "GEN.1.1", lines)).isFalse();
        assertThat(service.verify(new Attestation(a.alg(), a.kid(), a.canon(), a.corpus(),
            a.minted(), "0.9.9", a.tag()), "kjv", "GEN.1.1", lines)).isFalse();
    }

    // A saved XML file must be self-validating: the PI carries every field verify needs
    @Test
    void processingInstructionCarriesEveryField() {
        final Attestation a = service.mintLines("kjv", "GEN.1.1", List.of(new Line(1, "x")));
        final String pi = a.processingInstruction();
        assertThat(pi).startsWith("<?common-root-attestation ").endsWith("?>")
            .contains("alg=\"HMAC-SHA256\"").contains("kid=\"k1\"").contains("canon=\"v1\"")
            .contains("corpus=\"corpus-abc\"").contains("minted=\"" + MINTED + "\"")
            .contains("app=\"" + APP + "\"").contains("tag=\"" + a.tag() + "\"");
    }
}
