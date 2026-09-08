// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.religioustext.app.service.AttestationService;
import org.religioustext.app.service.AttestationService.Attestation;
import org.religioustext.app.service.AttestationService.Line;
import org.religioustext.app.service.AttestationService.Passage;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /api/v1/verify} (docs/api-spec.md §3.7): is this content, claimed
 * to be from here, actually ours — unmodified, for the corpus version it names?
 *
 * <pre>
 * { "text": "bible-kjv", "ref": "JHN.3.16",
 *   "attestation": { "alg": "HMAC-SHA256", "kid": "k1", "canon": "v1", "corpus": "…",
 *                    "minted": "2026-09-06T14:02:11Z", "app": "0.8.0", "tag": "…" },
 *   "verses": [ { "number": 16, "text": "For God so loved the world, …" } ] }
 * </pre>
 * or, for a scattered-passages response — one tag over the combination of all
 * the texts — the passages as served:
 * <pre>
 * { "attestation": { … },
 *   "passages": [ { "text": "bible-kjv-1611", "ref": "JHN.3.16", "verses": [ … ] },
 *                 { "text": "bible-web",      "ref": "JHN.3.16", "verses": [ … ] } ] }
 * </pre>
 * Each passage names its edition ({@code text}); a top-level {@code text} is the
 * default for passages that omit it.
 *
 * <p>The answer is {@code valid: true} with a sentence saying what that attests,
 * or {@code valid: false} with <em>no hint of why</em> — every failure cause
 * produces the same body, because a reason is an oracle for forging. Cost is
 * one HMAC and no corpus read; the body is capped at {@value #MAX_BODY_BYTES}
 * bytes, enough for any honest quote bundle and no use as a hashing service.
 *
 * <p>Verse text is compared as the reader displays it (the documented cleaning,
 * then NFC) — a JSON consumer already holds that form; an XML consumer applies
 * the cleaning first.
 *
 * <p>Sits behind the API key like every other route for now; the spec
 * recommends making this one keyless (§10.8) and that remains Christa's call.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@RestController
@RequestMapping("/api/v1/verify")
public class ApiVerifyController {

    static final int MAX_BODY_BYTES = 64 * 1024;
    static final int MAX_VERSES     = 400;   // Psalm 119 is 176; nothing honest is larger than a chapter or two

    private final AttestationService attestation;

    public ApiVerifyController(final AttestationService anAttestationService) {
        this.attestation = anAttestationService;
    }

    /** The request body. Unknown fields are ignored; missing ones fail closed.
     *  Either {@code ref}+{@code verses} (one passage) or {@code passages} (a bundle). */
    public record VerifyRequest(String text, String ref, Attestation attestation,
                                List<VerseIn> verses, List<PassageIn> passages) { }

    /** One verse as the caller holds it. */
    public record VerseIn(Integer number, String text) { }

    /** One passage of a bundle as the caller holds it; {@code text} defaults to the top-level one. */
    public record PassageIn(String text, String ref, List<VerseIn> verses) { }

    /**
     * Verify a presented bundle.
     *
     * @param aRequest the bundle
     * @return {@code valid} and, when true, what is attested
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> verify(@RequestBody final VerifyRequest aRequest) {
        if (aRequest == null || aRequest.attestation() == null) {
            return ResponseEntity.status(400).body(ApiError.of(400, "bad_request",
                "Body needs text, attestation{alg,kid,canon,corpus,minted,app,tag} and either ref+verses[{number,text}] "
                + "or passages[{ref,verses}].", "verify"));
        }
        // Normalise both shapes to a bundle.
        final List<PassageIn> presented = new ArrayList<>();
        if (aRequest.passages() != null && !aRequest.passages().isEmpty()) {
            presented.addAll(aRequest.passages());
        } else if (aRequest.verses() != null && !aRequest.verses().isEmpty()) {
            presented.add(new PassageIn(aRequest.text(), aRequest.ref(), aRequest.verses()));
        } else {
            return ResponseEntity.status(400).body(ApiError.of(400, "bad_request",
                "Nothing to verify: give ref+verses or passages.", "verify"));
        }

        final List<Passage> bundle = new ArrayList<>();
        final List<String> refs = new ArrayList<>();
        long bytes = 0;
        int verseCount = 0;
        for (final PassageIn p : presented) {
            if (p == null || p.verses() == null) return invalid();
            final List<Line> lines = new ArrayList<>();
            for (final VerseIn v : p.verses()) {
                if (v == null || v.number() == null) return invalid();
                final String text = v.text() == null ? "" : v.text();
                bytes += text.length();
                if (++verseCount > MAX_VERSES || bytes > MAX_BODY_BYTES) {
                    return ResponseEntity.status(413).body(ApiError.of(413, "too_large",
                        "Verification bodies are capped at " + MAX_VERSES + " verses / "
                        + MAX_BODY_BYTES + " bytes.", "verify"));
                }
                lines.add(new Line(v.number(), text));
            }
            final String edition = p.text() != null && !p.text().isBlank() ? p.text() : aRequest.text();
            bundle.add(new Passage(edition, p.ref(), lines));
            refs.add(edition + " " + p.ref());
        }

        final boolean valid = attestation.verifyBundle(aRequest.attestation(), bundle);
        if (!valid) return invalid();

        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", true);
        body.put("attests", "Served by common-root.org on " + aRequest.attestation().minted()
            + " (build " + aRequest.attestation().app() + "): " + String.join("; ", refs)
            + " — corpus " + aRequest.attestation().corpus() + ", unmodified.");
        body.put("minted", aRequest.attestation().minted());
        body.put("app", aRequest.attestation().app());
        // "current": a fresh fetch would return this same text; "superseded": authentic,
        // but from an earlier publish — today's text may differ. Validity is unaffected.
        body.put("corpus", attestation.corpusHash().equals(aRequest.attestation().corpus())
            ? "current" : "superseded");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The one and only failure body — byte-identical whatever went wrong. */
    private static ResponseEntity<Object> invalid() {
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", false);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
