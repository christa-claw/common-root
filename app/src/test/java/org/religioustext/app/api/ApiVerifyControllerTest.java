package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.api.ApiVerifyController.PassageIn;
import org.religioustext.app.api.ApiVerifyController.VerifyRequest;
import org.religioustext.app.api.ApiVerifyController.VerseIn;
import org.religioustext.app.service.AttestationService;
import org.religioustext.app.service.AttestationService.Attestation;
import org.religioustext.app.service.AttestationService.Line;
import org.religioustext.app.service.AttestationService.Passage;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verify route against a real {@link AttestationService} — the attest
 * method exercised through its public door (docs/api-test-cases.md A6, A7, A8,
 * A11): a minted bundle round-trips, every tampering yields the same opaque
 * body, and the caps hold.
 */
class ApiVerifyControllerTest {

    private final AttestationService attest =
        new AttestationService("k1", "test-secret", "", "corpus-test", "0.8.0-test", Clock.systemUTC());
    private final ApiVerifyController controller = new ApiVerifyController(attest);

    private static Passage kjv() {
        return new Passage("kjv", "JHN.3.16", List.of(new Line(16, "For God so loved the world")));
    }

    private static Passage web() {
        return new Passage("web", "JHN.3.16", List.of(new Line(16, "For God so loved the world, that he gave")));
    }

    private static PassageIn in(final Passage aPassage) {
        return new PassageIn(aPassage.text(), aPassage.ref(),
            aPassage.lines().stream().map(l -> new VerseIn(l.number(), l.text())).toList());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(final ResponseEntity<Object> aResponse) {
        return (Map<String, Object>) aResponse.getBody();
    }

    // A6 — a single passage, minted by the service, verifies through the route
    @Test
    void singlePassageRoundTrips() {
        final Attestation tag = attest.mintBundle(List.of(kjv()));
        final VerifyRequest request = new VerifyRequest("kjv", "JHN.3.16", tag,
            List.of(new VerseIn(16, "For God so loved the world")), null);

        final ResponseEntity<Object> response = controller.verify(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body(response)).containsEntry("valid", true).containsEntry("corpus", "current");
        assertThat((String) body(response).get("attests")).contains("kjv JHN.3.16").contains("unmodified")
            .contains("build 0.8.0-test").contains("Served by common-root.org on 20");
        assertThat(body(response)).containsKeys("minted", "app");
    }

    // A6 — a cross-edition bundle verifies as one object
    @Test
    void bundleRoundTrips() {
        final Attestation tag = attest.mintBundle(List.of(kjv(), web()));
        final VerifyRequest request = new VerifyRequest(null, null, tag, null, List.of(in(kjv()), in(web())));

        final ResponseEntity<Object> response = controller.verify(request);

        assertThat(body(response)).containsEntry("valid", true);
        assertThat((String) body(response).get("attests")).contains("kjv JHN.3.16").contains("web JHN.3.16");
    }

    // A7/A8 — every failure is the same opaque body: {"valid": false}, nothing else
    @Test
    void everyFailureIsTheSameOpaqueBody() {
        final Attestation tag = attest.mintBundle(List.of(kjv(), web()));
        final List<VerifyRequest> tampered = new ArrayList<>();
        // text changed
        tampered.add(new VerifyRequest(null, null, tag, null, List.of(
            new PassageIn("kjv", "JHN.3.16", List.of(new VerseIn(16, "For God so liked the world"))), in(web()))));
        // edition relabelled
        tampered.add(new VerifyRequest(null, null, tag, null, List.of(
            new PassageIn("niv", "JHN.3.16", kjv().lines().stream().map(l -> new VerseIn(l.number(), l.text())).toList()), in(web()))));
        // order swapped
        tampered.add(new VerifyRequest(null, null, tag, null, List.of(in(web()), in(kjv()))));
        // a passage dropped
        tampered.add(new VerifyRequest(null, null, tag, null, List.of(in(kjv()))));
        // verse number changed
        tampered.add(new VerifyRequest(null, null, tag, null, List.of(
            new PassageIn("kjv", "JHN.3.16", List.of(new VerseIn(17, "For God so loved the world"))), in(web()))));
        // wrong corpus label
        tampered.add(new VerifyRequest(null, null,
            new Attestation(tag.alg(), tag.kid(), tag.canon(), "corpus-other", tag.minted(), tag.app(), tag.tag()), null, List.of(in(kjv()), in(web()))));
        // unknown kid
        tampered.add(new VerifyRequest(null, null,
            new Attestation(tag.alg(), "k9", tag.canon(), tag.corpus(), tag.minted(), tag.app(), tag.tag()), null, List.of(in(kjv()), in(web()))));

        for (final VerifyRequest r : tampered) {
            final ResponseEntity<Object> response = controller.verify(r);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(body(response)).isEqualTo(Map.of("valid", false));   // no reason, ever
        }
    }

    // A past corpus label verifies by recomputation — the tag names the version
    @Test
    void pastCorpusLabelStillVerifies() {
        final Attestation minted = attest.mintBundle(List.of(kjv()));
        final AttestationService later = new AttestationService("k1", "test-secret", "", "corpus-NEXT", "0.8.1-test", Clock.systemUTC());
        final ApiVerifyController laterController = new ApiVerifyController(later);

        final ResponseEntity<Object> response = laterController.verify(
            new VerifyRequest(null, null, minted, null, List.of(in(kjv()))));

        assertThat(body(response)).containsEntry("valid", true).containsEntry("corpus", "superseded");
    }

    // 400s — a body that cannot be verified is refused, not "invalid"
    @Test
    void missingPartsAre400() {
        final Attestation tag = attest.mintBundle(List.of(kjv()));
        assertThat(controller.verify(null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.verify(new VerifyRequest("kjv", "JHN.3.16", null,
            List.of(new VerseIn(16, "x")), null)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.verify(new VerifyRequest("kjv", "JHN.3.16", tag, List.of(), null))
            .getStatusCode().value()).isEqualTo(400);
    }

    // A11 — caps
    @Test
    void oversizedBodiesAre413() {
        final Attestation tag = attest.mintBundle(List.of(kjv()));
        final List<VerseIn> many = new ArrayList<>();
        for (int i = 1; i <= ApiVerifyController.MAX_VERSES + 1; i++) many.add(new VerseIn(i, "x"));
        assertThat(controller.verify(new VerifyRequest("kjv", "PSA.119", tag, many, null))
            .getStatusCode().value()).isEqualTo(413);

        final List<VerseIn> huge = List.of(new VerseIn(1, "x".repeat(ApiVerifyController.MAX_BODY_BYTES + 1)));
        assertThat(controller.verify(new VerifyRequest("kjv", "GEN.1.1", tag, huge, null))
            .getStatusCode().value()).isEqualTo(413);
    }
}
