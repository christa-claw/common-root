package org.religioustext.app.ui.views.reader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link SourceCatalog}. Each pins the behaviour of the
 * ReaderView helper the catalog replaces (sourceById, sourceByToken, isQuranSource,
 * primarySources, and the setCompanion / translationFlags companion loops).
 */
class SourceCatalogTest {

    private static String[] bible() {
        return new String[]{"bible-niv", "New International Version", "niv", "ltr",
                            "\u00A9 Biblica", "https://biblica.com", "bible", "en", ""};
    }
    private static String[] quranBase() {
        return new String[]{"quran-ar", "Qur'an (Arabic)", "q-ar", "rtl",
                            "Public Domain", "tanzil.net", "quran", "ar", ""};
    }
    private static String[] quranSahih() {
        return new String[]{"quran-en-sahih", "Sahih International", "q-en", "ltr",
                            "Public Domain", "tanzil.net", "quran", "en", "quran-ar"};
    }
    private static String[] quranPickthall() {
        return new String[]{"quran-en-pick", "Pickthall", "q-pick", "ltr",
                            "Public Domain", "tanzil.net", "quran", "en", "quran-ar"};
    }

    private static SourceCatalog catalog() {
        return new SourceCatalog(List.of(bible(), quranBase(), quranSahih(), quranPickthall()));
    }

    // ── lineage fixtures: WEB←ASV←RV←KJV←{GNV,TR}; GNV←TR (diamond) ──
    private static String[] web() { return new String[]{"bible-web","World English Bible","web","ltr","PD","","bible","en","","bible-asv"}; }
    private static String[] asv() { return new String[]{"bible-asv","American Standard Version","asv","ltr","PD","","bible","en","","bible-rv"}; }
    private static String[] rv()  { return new String[]{"bible-rv","Revised Version","rv","ltr","PD","","bible","en","","bible-kjv"}; }
    private static String[] kjv() { return new String[]{"bible-kjv","King James Version","kjv","ltr","PD","","bible","en","","bible-gnv,bible-tr"}; }
    private static String[] gnv() { return new String[]{"bible-gnv","Geneva 1599","gnv","ltr","PD","","bible","en","","bible-tr"}; }
    private static String[] tr()  { return new String[]{"bible-tr","Textus Receptus","tr","ltr","PD","","bible","grc",""}; }

    private static SourceCatalog lineageCatalog() {
        return new SourceCatalog(List.of(web(), asv(), rv(), kjv(), gnv(), tr()));
    }

    @Test
    void lineageWalksGenerationsBreadthFirstMainLineFirst() {
        final List<List<SourceCatalog.Rung>> gens = lineageCatalog().lineageGenerationsOf("bible-web");
        assertThat(gens).hasSize(4);
        assertThat(gens.get(0)).extracting(r -> r.row()[0]).containsExactly("bible-asv");
        assertThat(gens.get(3)).extracting(r -> r.row()[0]).containsExactly("bible-gnv", "bible-tr");
    }

    @Test
    void lineageDiamondClaimsEachTextAtItsShallowestGeneration() {
        // KJV's parents are GNV and TR; GNV's parent TR is already claimed in
        // generation 0, so no deeper generation forms.
        assertThat(lineageCatalog().lineageGenerationsOf("bible-kjv")).hasSize(1);
    }

    @Test
    void lineageIsEmptyForRootsUnknownIdsAndNull() {
        assertThat(lineageCatalog().lineageGenerationsOf("bible-tr")).isEmpty();
        assertThat(lineageCatalog().lineageGenerationsOf("nope")).isEmpty();
        assertThat(lineageCatalog().lineageGenerationsOf(null)).isEmpty();
    }

    @Test
    void lineageAddsOriginalLanguageFloorForBibles() {
        final String[] niv = {"bible-niv2","New International Version","niv2","ltr","\u00A9","","bible","en",""};
        final String[] wlc = {"bible-wlc","Westminster Leningrad Codex","wlc","rtl","PD","","bible","he","","","true"};
        final String[] trO = {"bible-tr2","Textus Receptus","tr2","ltr","PD","","bible","grc","","","true"};
        final SourceCatalog c = new SourceCatalog(List.of(niv, wlc, trO));
        // No recorded chain -> exactly one generation: the originals floor.
        final List<List<SourceCatalog.Rung>> gens = c.lineageGenerationsOf("bible-niv2");
        assertThat(gens).hasSize(1);
        assertThat(gens.get(0)).extracting(r -> r.row()[0]).containsExactly("bible-wlc", "bible-tr2");
        assertThat(gens.get(0)).allMatch(r -> !r.attested());   // floor = marked witnesses
        // Originals themselves get no floor.
        assertThat(c.lineageGenerationsOf("bible-wlc")).isEmpty();
    }

    @Test
    void lineageFloorSkipsOriginalsTheChainAlreadyReached() {
        final String[] kjv = {"bible-kjv2","King James Version","kjv2","ltr","PD","","bible","en","","bible-wlc"};
        final String[] wlc = {"bible-wlc","Westminster Leningrad Codex","wlc","rtl","PD","","bible","he","","","true"};
        final String[] trO = {"bible-tr2","Textus Receptus","tr2","ltr","PD","","bible","grc","","","true"};
        final SourceCatalog c = new SourceCatalog(List.of(kjv, wlc, trO));
        final List<List<SourceCatalog.Rung>> gens = c.lineageGenerationsOf("bible-kjv2");
        // Generation 0 = the recorded parent (WLC); the floor adds only TR.
        assertThat(gens).hasSize(2);
        assertThat(gens.get(0)).extracting(r -> r.row()[0]).containsExactly("bible-wlc");
        assertThat(gens.get(0).get(0).attested()).isTrue();     // recorded parent stays plain
        assertThat(gens.get(1)).extracting(r -> r.row()[0]).containsExactly("bible-tr2");
        assertThat(gens.get(1).get(0).attested()).isFalse();    // floor witness is marked
    }

    @Test
    void lineageCyclesTerminate() {
        final String[] a = {"a","A","aa","ltr","","","bible","en","","b"};
        final String[] b = {"b","B","bb","ltr","","","bible","en","","a"};
        assertThat(new SourceCatalog(List.of(a, b)).lineageGenerationsOf("a")).hasSize(1);
    }

    @Test
    void byIdFindsRowOrNull() {
        assertThat(catalog().byId("quran-ar")[2]).isEqualTo("q-ar");
        assertThat(catalog().byId("nope")).isNull();
        assertThat(catalog().byId(null)).isNull();
    }

    @Test
    void byTokenIsCaseInsensitive() {
        assertThat(catalog().byToken("NIV")[0]).isEqualTo("bible-niv");
        assertThat(catalog().byToken("q-ar")[0]).isEqualTo("quran-ar");
        assertThat(catalog().byToken("missing")).isNull();
        assertThat(catalog().byToken(null)).isNull();
    }

    @Test
    void isQuranByIdMatchesType() {
        assertThat(catalog().isQuran("quran-ar")).isTrue();
        assertThat(catalog().isQuran("quran-en-sahih")).isTrue();   // a translation is still quran-typed
        assertThat(catalog().isQuran("bible-niv")).isFalse();
        assertThat(catalog().isQuran("unknown")).isFalse();
    }

    @Test
    void primariesExcludeTranslations() {
        assertThat(catalog().primaries().stream().map(r -> r[0]).toList())
            .containsExactly("bible-niv", "quran-ar");   // bible + Arabic base, not the two translations
    }

    @Test
    void translationsOfBaseText() {
        assertThat(catalog().translationsOf("quran-ar").stream().map(r -> r[0]).toList())
            .containsExactly("quran-en-sahih", "quran-en-pick");
        assertThat(catalog().translationsOf("bible-niv")).isEmpty();   // Bibles have no companions
        assertThat(catalog().translationsOf(null)).isEmpty();
    }

    @Test
    void nullRowsListYieldsEmptyCatalog() {
        final SourceCatalog empty = new SourceCatalog(null);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.byId("x")).isNull();
        assertThat(empty.primaries()).isEmpty();
    }

    // ── descendants: the same ladder walked the other way ───────────────────

    @Test
    void descendantsWalkGenerationsAwayFromTheEdition() {
        // TR is named by KJV and GNV; then RV, then ASV, then WEB.
        final List<List<SourceCatalog.Rung>> gens = lineageCatalog().descendantGenerationsOf("bible-tr");
        assertThat(gens).hasSize(4);
        assertThat(gens.get(0)).extracting(r -> r.row()[0])
            .containsExactly("bible-kjv", "bible-gnv");
        assertThat(gens.get(1)).extracting(r -> r.row()[0]).containsExactly("bible-rv");
        assertThat(gens.get(2)).extracting(r -> r.row()[0]).containsExactly("bible-asv");
        assertThat(gens.get(3)).extracting(r -> r.row()[0]).containsExactly("bible-web");
    }

    @Test
    void descendantDiamondClaimsEachTextAtItsShallowestGeneration() {
        // GNV is a child of TR directly AND reachable through KJV. Claimed once,
        // in generation 0, exactly as the ancestor walk claims a shared parent.
        final List<List<SourceCatalog.Rung>> gens = lineageCatalog().descendantGenerationsOf("bible-tr");
        assertThat(gens.stream().flatMap(List::stream).map(r -> r.row()[0]))
            .containsExactlyInAnyOrder("bible-kjv", "bible-gnv", "bible-rv", "bible-asv", "bible-web");
    }

    @Test
    void descendantsAreEmptyForLeavesUnknownIdsAndNull() {
        assertThat(lineageCatalog().descendantGenerationsOf("bible-web")).isEmpty();
        assertThat(lineageCatalog().descendantGenerationsOf("nope")).isEmpty();
        assertThat(lineageCatalog().descendantGenerationsOf(null)).isEmpty();
    }

    @Test
    void everyDescendantRungIsAttested() {
        // No marked rungs on this side: the descendant walk follows recorded
        // @basedOn only, so each link is a claim the descendant makes itself.
        assertThat(lineageCatalog().descendantGenerationsOf("bible-tr").stream()
            .flatMap(List::stream)).allMatch(SourceCatalog.Rung::attested);
    }

    @Test
    void theOriginalLanguageFloorIsNotInverted() {
        // Upwards, EVERY Bible is shown the WLC as an unattested witness. If that
        // were mirrored, the WLC would become the ancestor of the whole corpus
        // and the hedge would turn into an assertion. Nothing is based on this
        // WLC, so it has no descendants — even though Bibles exist beside it.
        final String[] wlc = {"bible-wlc","Westminster Leningrad Codex","wlc","rtl","PD","","bible","he","","","true"};
        final String[] plain = {"bible-x","Some Bible","x","ltr","PD","","bible","en",""};
        final SourceCatalog c = new SourceCatalog(List.of(wlc, plain));
        assertThat(c.lineageGenerationsOf("bible-x")).isNotEmpty();          // floor gives it the WLC
        assertThat(c.descendantGenerationsOf("bible-wlc")).isEmpty();        // but not the reverse
    }
}
