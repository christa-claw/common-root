package org.religioustext.app.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.religioustext.app.web.CorpusDownloadController.authenticCopyQuery;
import static org.religioustext.app.web.CorpusDownloadController.isPublicDomain;

/**
 * The licence gate on the corpus download endpoint, and the strip that makes a
 * download an authentic copy. Both are tested as pure functions rather than trusted
 * to a code read: every widening of the gate is a licence decision, and a test that
 * fails is the intended way to notice one.
 */
class CorpusDownloadControllerTest {

    @Test
    void publicDomainIsServed() {
        assertThat(isPublicDomain("Public Domain")).isTrue();
        assertThat(isPublicDomain("public domain")).isTrue();
        assertThat(isPublicDomain("  Public Domain  ")).isTrue();
    }

    @Test
    void theLdsWordingIsStillPublicDomain() {
        // ingest_bom.py writes this; a prefix match covers it without enumerating variants.
        assertThat(isPublicDomain(
            "Public domain (LDS standard works; copyrighted apparatus excluded)")).isTrue();
    }

    @Test
    void licensedTextsAreRefused() {
        // NIV, NASB, NBLA, AEUUT, NAV. The About page promises these are not
        // redistributed; this method is what makes that true.
        assertThat(isPublicDomain("Licensed")).isFalse();
        assertThat(isPublicDomain("licensed")).isFalse();
    }

    @Test
    void ccLicencesAreRefusedUntilDeliberatelyAllowed() {
        // Both DO permit verbatim redistribution, so this is a choice, not an oversight:
        // serving them needs the attribution notice travelling with the file, and for
        // YTC the no-derivatives question in the deploy checklist settled first.
        assertThat(isPublicDomain("CC BY-SA 4.0 — Bridge Connectivity Solutions")).isFalse();
        assertThat(isPublicDomain("CC BY-ND 4.0")).isFalse();
    }

    @Test
    void jurisdictionallyQualifiedPublicDomainIsRefused() {
        // THE ONE THAT ALMOST GOT THROUGH. Yusuf Ali is public domain in the EU and
        // Pakistan and under US URAA copyright until 2033. A prefix match on
        // "public domain" served it worldwide. The endpoint cannot know where the
        // requester is, so a licence that depends on that is not one it can honour.
        assertThat(isPublicDomain(
            "Public domain in EU/life+70 & Pakistan (A. Yusuf Ali, 1934); "
            + "US URAA copyright to 2033")).isFalse();
        assertThat(isPublicDomain("Public domain in the EU")).isFalse();
        assertThat(isPublicDomain("Public domain except in the US")).isFalse();
    }

    @Test
    void parentheticalAttributionIsStillUnqualifiedPublicDomain() {
        assertThat(isPublicDomain("Public domain (M. Pickthall, 1930)")).isTrue();
        assertThat(isPublicDomain("Public domain (G. Sablukov, 1878; author d.1880)")).isTrue();
    }

    @Test
    void anythingUnknownOrAbsentFailsClosed() {
        assertThat(isPublicDomain(null)).isFalse();
        assertThat(isPublicDomain("")).isFalse();
        assertThat(isPublicDomain("   ")).isFalse();
        assertThat(isPublicDomain("Arabic public domain · English under review")).isFalse();
        assertThat(isPublicDomain("unknown")).isFalse();
    }

    @Test
    void theMatchIsAPrefixNotASubstring() {
        // "Not public domain" must never pass by containing the words.
        assertThat(isPublicDomain("Not public domain")).isFalse();
        assertThat(isPublicDomain("Formerly public domain, now licensed")).isFalse();
    }

    // ── The strip: what leaves here is the edition, not our numbering ────────

    @Test
    void allThreeSequenceAttributesAreDeleted() {
        // Stamped by scripts/bibles/04-06 for the reader's verse windows, column
        // sync and alternate reading orders. None of them is part of any edition.
        final String q = authenticCopyQuery("religioustext", "bible-web");
        assertThat(q).contains("$doc//@globalCanonicalSeq")
                     .contains("$doc//@globalChronologicalSeq")
                     .contains("$doc//@globalTanakhSeq");
    }

    @Test
    void theStripIsATransformSoTheStoredDocumentIsUntouched() {
        // copy/modify/return builds a throwaway copy. An XQuery Update against
        // db:open would delete the attributes out of the corpus itself and break
        // the reader for everyone the moment somebody downloaded a bible.
        final String q = authenticCopyQuery("religioustext", "bible-web");
        assertThat(q).contains("copy $doc := db:open('religioustext', 'bible-web.xml')")
                     .contains("modify delete node")
                     .contains("return $doc");
    }

    @Test
    void theDocumentIsServedWithItsXmlDeclaration() {
        // A downloaded file that omits <?xml ...?> is still well-formed but tells a
        // parser nothing about its encoding, and these texts are not all ASCII.
        assertThat(authenticCopyQuery("religioustext", "bible-agr-1548"))
            .contains("declare option output:omit-xml-declaration 'no'");
    }

    @Test
    void quotesInIdentifiersCannotEscapeTheStringLiteral() {
        // Ids come from the corpus rather than the URL, but the query is assembled
        // by concatenation, and "it cannot happen" is not a mechanism.
        final String q = authenticCopyQuery("religioustext", "bible-o'brien");
        assertThat(q).contains("'bible-o''brien.xml'");
    }
}
