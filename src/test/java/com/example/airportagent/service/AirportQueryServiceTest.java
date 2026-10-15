package com.example.airportagent.service;

import java.util.Map;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.FaaStatusClient;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Contribution;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.LiveStatus;
import com.example.airportagent.domain.Score;
import com.example.airportagent.service.Views.AirportProfileView;
import com.example.airportagent.service.Views.ComparisonView;
import com.example.airportagent.service.Views.LiveStatusView;
import com.example.airportagent.service.Views.LongHaulView;
import com.example.airportagent.service.Views.RankedItem;
import com.example.airportagent.service.Views.RankingView;
import com.example.airportagent.service.Views.Resolution;
import com.example.airportagent.service.Views.UnmetDemandView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** Tests the service with the real data files and the real settings of application.yml. */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
@Import(AirportQueryServiceTest.FakeLive.class)
class AirportQueryServiceTest {

    static final AtomicBoolean LIVE_FAILS = new AtomicBoolean(false);

    @TestConfiguration
    static class FakeLive {
        @Bean
        @Primary
        FaaStatusClient fakeFaaStatusClient() {
            return code -> {
                if (LIVE_FAILS.get()) {
                    throw new IOException("timeout");
                }
                return new LiveStatus(code, "test time", List.of(), 0);
            };
        }
    }

    @Autowired
    private AirportQueryService service;
    @Autowired
    private AirportDataset data;

    @SuppressWarnings("unchecked")
    private static <T> QueryResult.Ok<T> ok(QueryResult<T> result) {
        assertThat(result).isInstanceOf(QueryResult.Ok.class);
        return (QueryResult.Ok<T>) result;
    }

    // ---------------------------------------------------------------- ranking

    /** Checks that new england terminal ranking starts with boston and stays in the region. */
    @Test
    void newEnglandTerminalRankingStartsWithBostonAndStaysInTheRegion() {
        QueryResult.Ok<RankingView> result = ok(service.rank("NEW_ENGLAND", "terminal", 10));

        List<RankedItem> items = result.data().items();
        assertThat(items.get(0).airport().iata()).isEqualTo("BOS");
        assertThat(items).extracting(i -> i.airport().state()).isSubsetOf("CT", "MA", "ME", "NH", "RI", "VT");
        assertThat(items).extracting(RankedItem::rank).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, items.size()).boxed().toList());
        assertThat(items).extracting(RankedItem::nationalRank).isSorted();
        assertThat(items).extracting(i -> i.score().value()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(result.data().profile()).isEqualTo("terminal");
        assertThat(result.envelope().profile()).isEqualTo("terminal");
    }

    /** Checks that the national terminal top ten are large airports and a fast growing mid size airport is below them. */
    @Test
    void theNationalTerminalTopTenAreLargeAirportsAndAFastGrowingMidSizeAirportIsBelowThem() {
        List<RankedItem> items = ok(service.rank("ALL", "terminal", 50)).data().items();

        // Size is part of the score. A mid-size airport with fast growth (Provo) must not be near the top.
        assertThat(items.subList(0, 10)).allSatisfy(item -> assertThat(
                data.enplanements().get(item.airport().iata()).cy25().orElse(0)).as(item.airport().iata())
                .isGreaterThan(10_000_000L));
        int jfk = items.stream().map(i -> i.airport().iata()).toList().indexOf("JFK");
        int provo = items.stream().map(i -> i.airport().iata()).toList().indexOf("PVU");
        assertThat(jfk).isNotNegative();
        assertThat(provo).isGreaterThan(jfk);
    }

    /** Checks that ranking uses only airports above the size limit. */
    @Test
    void rankingUsesOnlyAirportsAboveTheSizeLimit() {
        QueryResult.Ok<RankingView> result = ok(service.rank("ALL", "general", 50));

        assertThat(result.data().items()).hasSize(50);
        assertThat(result.data().rankedSetSize()).isBetween(150, 300);
        for (RankedItem item : result.data().items()) {
            assertThat(data.enplanements().get(item.airport().iata()).cy25().orElse(0))
                    .as(item.airport().iata()).isGreaterThanOrEqualTo(result.data().minEnplanements());
        }
    }

    /** Checks that ranking shows lower coverage of airports without delay data. */
    @Test
    void rankingShowsLowerCoverageOfAirportsWithoutDelayData() {
        QueryResult.Ok<RankingView> result = ok(service.rank("NEW_ENGLAND", "terminal", 10));

        Score hvn = result.data().items().stream().filter(i -> i.airport().iata().equals("HVN")).findFirst()
                .orElseThrow().score();
        assertThat(hvn.coverage()).isLessThan(0.9);
        assertThat(hvn.confidence()).isNotEqualTo(Confidence.HIGH);
        assertThat(hvn.missingKpis()).contains(Kpi.DELAY15_RATE, Kpi.TAXI_OUT_MIN, Kpi.CANCEL_RATE);
        assertThat(result.envelope().uncertainties()).anyMatch(text -> text.startsWith("HVN: no data for"));
    }

    /** Checks that ranking states the limits in the envelope. */
    @Test
    void rankingStatesTheLimitsInTheEnvelope() {
        QueryResult.Ok<RankingView> result = ok(service.rank("NEW_ENGLAND", "terminal", 5));

        assertThat(result.envelope().assumptions()).anyMatch(text -> text.contains("heuristics"));
        assertThat(result.envelope().outOfScope()).anyMatch(text -> text.contains("cost, revenue or profit"));
        assertThat(result.envelope().parameters()).containsEntry("region", "NEW_ENGLAND").containsEntry("topN", "5");
        assertThat(result.envelope().sources()).isNotEmpty();
    }

    /** Checks that top n is limited to the allowed range. */
    @Test
    void topNIsLimitedToTheAllowedRange() {
        assertThat(ok(service.rank("ALL", "general", 1000)).data().items()).hasSize(AirportQueryService.MAX_TOP_N);
        assertThat(ok(service.rank("ALL", "general", 0)).data().items()).hasSize(1);
        assertThat(ok(service.rank("ALL", null, null)).data().items()).hasSize(AirportQueryService.DEFAULT_TOP_N);
    }

    /** Checks that unknown profile or region gives a clear gap. */
    @Test
    void unknownProfileOrRegionGivesAClearGap() {
        assertThat(service.rank("NEW_ENGLAND", "nonsense", 5)).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                na -> assertThat(na.whatIsAvailable()).contains("terminal", "general"));
        assertThat(service.rank("MARS", "terminal", 5)).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                na -> assertThat(na.reason()).contains("Unknown region"));
    }

    /** Checks that regions can be a name a state code or a list. */
    @Test
    void regionsCanBeANameAStateCodeOrAList() {
        assertThat(ok(service.rank("WEST_COAST", "general", 50)).data().items())
                .extracting(i -> i.airport().state()).isSubsetOf("CA", "OR", "WA").contains("CA", "WA");
        assertThat(ok(service.rank("TX", "general", 50)).data().items())
                .extracting(i -> i.airport().state()).containsOnly("TX");
        assertThat(ok(service.rank("CA, OR", "general", 50)).data().items())
                .extracting(i -> i.airport().state()).containsOnly("CA", "OR");
        assertThat(ok(service.rank("new england", "terminal", 3)).data().region()).isEqualTo("NEW_ENGLAND");
        assertThat(ok(service.rank("ALL", "terminal", 3)).data().region()).isEqualTo("ALL");
        assertThat(ok(service.rank(null, "terminal", 3)).data().region()).isEqualTo("ALL");
    }

    /** Checks that a broad region contains its divisions. */
    @Test
    void aBroadRegionContainsItsDivisions() {
        List<String> northeast = ok(service.rank("NORTHEAST", "general", 50)).data().items().stream()
                .map(i -> i.airport().iata()).toList();

        assertThat(northeast).contains("BOS", "JFK", "PHL", "PWM");
    }

    /** Checks that a region rank is counted inside the region and the national rank is kept. */
    @Test
    void aRegionRankIsCountedInsideTheRegionAndTheNationalRankIsKept() {
        RankedItem first = ok(service.rank("TX", "general", 1)).data().items().get(0);

        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.nationalRank()).isGreaterThan(1);
    }

    /** Checks that an unknown region lists the known names. */
    @Test
    void anUnknownRegionListsTheKnownNames() {
        assertThat(service.rank("Atlantis", "general", 5)).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                na -> assertThat(na.whatIsAvailable().toString()).contains("NEW_ENGLAND", "WEST_COAST", "ALL"));
    }

    // ---------------------------------------------------------------- one airport

    /** Checks that airport profile shows kpis with quality tags and percentiles. */
    @Test
    void airportProfileShowsKpisWithQualityTagsAndPercentiles() {
        AirportProfileView view = ok(service.airportProfile("SFO", "terminal")).data();

        assertThat(view.inRankedSet()).isTrue();
        assertThat(view.kpis()).hasSize(Kpi.values().length);
        assertThat(view.kpis()).filteredOn(k -> k.kpi() == Kpi.LOAD_FACTOR).singleElement().satisfies(k -> {
            assertThat(k.quality()).isEqualTo("PROXY");
            assertThat(k.percentile()).isBetween(0.0, 100.0);
            assertThat(k.note()).contains("terminal pressure");
        });
        assertThat(view.kpis()).filteredOn(k -> k.kpi() == Kpi.GROWTH).singleElement()
                .satisfies(k -> assertThat(k.quality()).isEqualTo("DIRECT"));
        double points = view.score().contributions().stream().mapToDouble(Contribution::points).sum();
        assertThat(points).isCloseTo(view.score().value(), org.assertj.core.data.Offset.offset(1e-9));
    }

    /** Checks that an airport below the size limit is marked and not ranked. */
    @Test
    void anAirportBelowTheSizeLimitIsMarkedAndNotRanked() {
        QueryResult<AirportProfileView> result = service.airportProfile("PSM", "general");

        assertThat(result).isInstanceOf(QueryResult.Partial.class);
        QueryResult.Partial<AirportProfileView> partial = (QueryResult.Partial<AirportProfileView>) result;
        assertThat(partial.missing()).anyMatch(text -> text.contains("below the size limit"));
        assertThat(partial.data().inRankedSet()).isFalse();
        assertThat(ok(service.rank("NEW_ENGLAND", "general", 50)).data().items())
                .extracting(i -> i.airport().iata()).doesNotContain("PSM");
    }

    /** Checks that an unknown airport is out of scope. */
    @Test
    void anUnknownAirportIsOutOfScope() {
        assertThat(service.airportProfile("ZZZ", "general")).isInstanceOfSatisfying(QueryResult.OutOfScope.class,
                out -> assertThat(out.scopeRule()).contains("Foreign airports are not in the data"));
        assertThat(service.airportProfile("LHR", "general")).isInstanceOf(QueryResult.OutOfScope.class);
    }

    // ---------------------------------------------------------------- comparison

    /** Checks that lax and sna comparison warns about different hub classes. */
    @Test
    void laxAndSnaComparisonWarnsAboutDifferentHubClasses() {
        QueryResult.Ok<ComparisonView> result = ok(service.compare(List.of("LAX", "SNA"), "congestion"));

        ComparisonView view = result.data();
        assertThat(view.airports()).extracting(a -> a.airport().iata()).containsExactly("LAX", "SNA");
        assertThat(view.warnings()).anyMatch(text -> text.contains("different FAA hub classes")
                && text.contains("LAX: LARGE") && text.contains("SNA: MEDIUM"));
        assertThat(view.airports()).allSatisfy(a -> assertThat(a.components()).containsKey(Component.CONGESTION));
        assertThat(view.airports()).allSatisfy(a -> assertThat(a.score().value())
                .isEqualTo(a.components().get(Component.CONGESTION)));
        assertThat(result.envelope().assumptions()).anyMatch(text -> text.contains("operations per runway"));
    }

    /** Checks that the comparison says which airport is higher and by how much. */
    @Test
    void theComparisonSaysWhichAirportIsHigherAndByHowMuch() {
        ComparisonView view = ok(service.compare(List.of("SNA", "LAX"), "congestion")).data();

        double lax = view.airports().stream().filter(a -> a.airport().iata().equals("LAX")).findFirst().orElseThrow()
                .components().get(Component.CONGESTION);
        double sna = view.airports().stream().filter(a -> a.airport().iata().equals("SNA")).findFirst().orElseThrow()
                .components().get(Component.CONGESTION);
        assertThat(view.focusOrder()).extracting(Views.Placement::iata)
                .containsExactly(lax >= sna ? "LAX" : "SNA", lax >= sna ? "SNA" : "LAX");
        assertThat(view.focusGapPoints()).isCloseTo(Math.abs(lax - sna), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.kpiComparisons()).extracting(Views.KpiComparison::kpi).contains(
                Kpi.DELAY15_RATE, Kpi.TAXI_OUT_MIN, Kpi.CANCEL_RATE, Kpi.OPS_PER_RUNWAY);
        Views.KpiComparison ops = view.kpiComparisons().stream().filter(k -> k.kpi() == Kpi.OPS_PER_RUNWAY)
                .findFirst().orElseThrow();
        assertThat(ops.highest()).isEqualTo("LAX");
        assertThat(ops.lowest()).isEqualTo("SNA");
    }

    /** Checks that a profile focus sorts by the score of that profile. */
    @Test
    void aProfileFocusSortsByTheScoreOfThatProfile() {
        ComparisonView view = ok(service.compare(List.of("SNA", "LAX", "BOS"), "terminal")).data();

        assertThat(view.focusOrder()).extracting(Views.Placement::value).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(view.focusOrder()).hasSize(3);
    }

    /** Checks that comparison with an unknown code is partial. */
    @Test
    void comparisonWithAnUnknownCodeIsPartial() {
        assertThat(service.compare(List.of("LAX", "ZZZ"), "congestion")).isInstanceOfSatisfying(
                QueryResult.Partial.class, partial -> assertThat(partial.missing().toString()).contains("ZZZ is not in the data"));
    }

    /** Checks that comparison needs at least two airports. */
    @Test
    void comparisonNeedsAtLeastTwoAirports() {
        assertThat(service.compare(List.of("LAX"), "congestion")).isInstanceOf(QueryResult.NotAvailable.class);
        assertThat(service.compare(null, "congestion")).isInstanceOf(QueryResult.NotAvailable.class);
    }

    // ---------------------------------------------------------------- long-haul share

    /** Checks that anchorage long haul shows both values and the cargo effect. */
    @Test
    void anchorageLongHaulShowsBothValuesAndTheCargoEffect() {
        QueryResult.Ok<LongHaulView> result = ok(service.longHaul("ANC", null));

        LongHaulView view = result.data();
        assertThat(view.limitUsedMiles()).isEqualTo(2500);
        assertThat(view.sharePassengerOnly()).isBetween(0.14, 0.16);
        assertThat(view.shareWithCargo()).isNotEqualTo(view.sharePassengerOnly());
        assertThat(view.cargoShareOfDepartures()).isGreaterThan(0.3);
        assertThat(view.table()).contains("domestic and international");
        assertThat(result.envelope().uncertainties()).anyMatch(text -> text.contains("Cargo flights are"));
        assertThat(result.envelope().uncertainties()).anyMatch(text -> text.contains("foreign airlines"));
    }

    /** Checks that long haul limit is rounded down and the answer says so. */
    @Test
    void longHaulLimitIsRoundedDownAndTheAnswerSaysSo() {
        QueryResult.Ok<LongHaulView> result = ok(service.longHaul("ANC", 2600));

        assertThat(result.data().limitWasRounded()).isTrue();
        assertThat(result.data().limitUsedMiles()).isEqualTo(2500);
        assertThat(result.envelope().uncertainties()).anyMatch(text -> text.contains("rounded down to 2500"));
    }

    /** Checks that a lower limit gives a higher share. */
    @Test
    void aLowerLimitGivesAHigherShare() {
        double at2500 = ok(service.longHaul("ANC", 2500)).data().sharePassengerOnly();
        double at1500 = ok(service.longHaul("ANC", 1500)).data().sharePassengerOnly();

        assertThat(at1500).isGreaterThan(at2500);
    }

    /** Checks that long haul rejects a negative limit and unknown airports. */
    @Test
    void longHaulRejectsANegativeLimitAndUnknownAirports() {
        assertThat(service.longHaul("ANC", -5)).isInstanceOf(QueryResult.NotAvailable.class);
        assertThat(service.longHaul("ZZZ", 2500)).isInstanceOf(QueryResult.OutOfScope.class);
    }

    // ---------------------------------------------------------------- unmet demand

    /** Checks that san francisco unmet demand is a labeled proxy with its reasons. */
    @Test
    void sanFranciscoUnmetDemandIsALabeledProxyWithItsReasons() {
        QueryResult.Ok<UnmetDemandView> result = ok(service.unmetDemand("SFO"));

        UnmetDemandView view = result.data();
        assertThat(view.label()).isEqualTo("PROXY");
        assertThat(view.explanation()).contains("proxy").contains("not a number of flights");
        double points = view.score().contributions().stream().mapToDouble(Contribution::points).sum();
        assertThat(view.index()).isCloseTo(points, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.score().contributions()).extracting(Contribution::component)
                .containsOnly(Component.LOAD_FACTOR, Component.GROWTH, Component.CONGESTION);
        assertThat(view.whyInPoints()).hasSize(3);
        assertThat(view.whyInPoints().get(0)).startsWith("LOAD_FACTOR");
        assertThat(view.expertNote()).contains("parallel runways");
        assertThat(view.expertNoteSource()).contains("expert note").contains("not calculated from the data");
        assertThat(result.envelope().outOfScope()).anyMatch(text -> text.contains("count of unserved flights"));
    }

    /** Checks that airports without an expert note have none. */
    @Test
    void airportsWithoutAnExpertNoteHaveNone() {
        assertThat(ok(service.unmetDemand("LAX")).data().expertNote()).isNull();
    }

    // ---------------------------------------------------------------- live status

    /** Checks that live status comes from the faa client. */
    @Test
    void liveStatusComesFromTheFaaClient() {
        LIVE_FAILS.set(false);

        QueryResult.Ok<LiveStatusView> result = ok(service.liveStatus("SFO"));

        assertThat(result.data().status().updateTime()).isEqualTo("test time");
        assertThat(result.envelope().assumptions()).anyMatch(text -> text.contains("does not change any score"));
    }

    /** Checks that a failed live call is a gap and not an error. */
    @Test
    void aFailedLiveCallIsAGapAndNotAnError() {
        LIVE_FAILS.set(true);
        try {
            assertThat(service.liveStatus("SFO")).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                    na -> assertThat(na.reason()).contains("not available now").contains("timeout"));
        } finally {
            LIVE_FAILS.set(false);
        }
    }

    // ---------------------------------------------------------------- name lookup

    /** Checks that names of the example questions resolve to the right airports. */
    @Test
    void namesOfTheExampleQuestionsResolveToTheRightAirports() {
        assertThat(resolved("LA")).isEqualTo("LAX");
        assertThat(resolved("Los Angeles")).isEqualTo("LAX");
        assertThat(resolved("santa ana")).isEqualTo("SNA");
        assertThat(resolved("Anchorage")).isEqualTo("ANC");
        assertThat(resolved("SFO")).isEqualTo("SFO");
        assertThat(resolved("KSFO")).isEqualTo("SFO");
        assertThat(resolved("palm beach")).isEqualTo("PBI");
        assertThat(resolved("Boston Logan")).isEqualTo("BOS");
    }

    /** Checks that an ambiguous name is not resolved and lists the options. */
    @Test
    void anAmbiguousNameIsNotResolvedAndListsTheOptions() {
        Resolution resolution = ok(service.resolveAirport("Portland")).data();

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.best()).isNull();
        assertThat(resolution.alternatives()).extracting(c -> c.airport().iata()).contains("PDX", "PWM");
    }

    /** Checks that unknown or empty names are out of scope. */
    @Test
    void unknownOrEmptyNamesAreOutOfScope() {
        assertThat(service.resolveAirport("Heathrow")).isInstanceOf(QueryResult.OutOfScope.class);
        assertThat(service.resolveAirport("  ")).isInstanceOf(QueryResult.OutOfScope.class);
        assertThat(service.resolveAirport(null)).isInstanceOf(QueryResult.OutOfScope.class);
    }

    /** Checks that the methodology shows the profiles from the settings. */
    @Test
    void theMethodologyShowsTheProfilesFromTheSettings() {
        Views.MethodologyView view = ok(service.methodology()).data();

        assertThat(view.weightProfiles()).containsKeys("terminal", "general");
        assertThat(view.weightProfiles().get("terminal").values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.unmetDemandWeights()).containsKeys("load_factor", "growth", "congestion");
        assertThat(view.rankedAirports()).isBetween(150, 300);
        assertThat(view.minEnplanements()).isEqualTo(100_000);
    }

    /** Checks that the tools accept a name and a code. */
    @Test
    void theToolsAcceptANameAndACode() {
        double byCode = ok(service.longHaul("ANC", 2500)).data().sharePassengerOnly();

        assertThat(ok(service.longHaul("Anchorage", 2500)).data().sharePassengerOnly()).isEqualTo(byCode);
        assertThat(ok(service.compare(List.of("LA", "Santa Ana"), "congestion")).data().airports())
                .extracting(a -> a.airport().iata()).containsExactly("LAX", "SNA");
    }

    /** Checks that a name with more than one airport asks the caller to choose. */
    @Test
    void aNameWithMoreThanOneAirportAsksTheCallerToChoose() {
        QueryResult<LongHaulView> result = service.longHaul("Portland", 2500);

        assertThat(result).isInstanceOfSatisfying(QueryResult.NotAvailable.class, na -> {
            assertThat(na.reason()).contains("more than one airport");
            assertThat(na.whatIsAvailable().toString()).contains("PWM (").contains("PDX (");
        });
    }

    /** Checks that a very long name is rejected before the search. */
    @Test
    void aVeryLongNameIsRejectedBeforeTheSearch() {
        assertThat(service.longHaul("A".repeat(AirportResolver.MAX_QUERY_LENGTH + 1), 2500))
                .isInstanceOf(QueryResult.OutOfScope.class);
    }

    private String resolved(String text) {
        Resolution resolution = ok(service.resolveAirport(text)).data();
        assertThat(resolution.resolved()).as(text).isTrue();
        return resolution.best().airport().iata();
    }

    // ---------------------------------------------------------------- custom weights (what-if)

    private static int positionOf(List<RankedItem> items, String iata) {
        return items.stream().map(i -> i.airport().iata()).toList().indexOf(iata);
    }

    /** Checks that ignoring scale moves a fast growing mid size airport up. */
    @Test
    void ignoringScaleMovesAFastGrowingMidSizeAirportUp() {
        List<RankedItem> normal = ok(service.rank("ALL", "terminal", 50)).data().items();
        QueryResult.Ok<RankingView> custom = ok(service.rank("ALL", "terminal", 50, "scale:0"));

        assertThat(custom.data().profile()).isEqualTo("custom");
        assertThat(custom.data().weights().get("scale")).isEqualTo(0.0);
        assertThat(custom.data().weights().values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        int before = positionOf(normal, "PVU");
        int after = positionOf(custom.data().items(), "PVU");
        assertThat(after).isGreaterThanOrEqualTo(0);
        assertThat(before < 0 || after < before).as("PVU moves up: before=" + before + " after=" + after).isTrue();
    }

    /** Checks that custom weights are labeled in the envelope. */
    @Test
    void customWeightsAreLabeledInTheEnvelope() {
        QueryResult.Ok<RankingView> result = ok(service.rank("NEW_ENGLAND", "terminal", 5, "congestion:0.5"));

        assertThat(result.envelope().assumptions()).anyMatch(text -> text.contains("custom weights from the user"));
        assertThat(result.envelope().parameters()).containsEntry("weights", "congestion:0.5");
        assertThat(result.data().weights().get("congestion")).isEqualTo(0.5);
    }

    /** Checks that weights can be fractions or percentages. */
    @Test
    void weightsCanBeFractionsOrPercentages() {
        Map<String, Double> fraction = ok(service.rank("ALL", "terminal", 3, "congestion:0.5")).data().weights();
        Map<String, Double> percent = ok(service.rank("ALL", "terminal", 3, "congestion:50%")).data().weights();
        Map<String, Double> number = ok(service.rank("ALL", "terminal", 3, "Congestion = 50")).data().weights();

        assertThat(percent).isEqualTo(fraction);
        assertThat(number).isEqualTo(fraction);
    }

    /** Checks that no weights give the normal profile. */
    @Test
    void noWeightsGiveTheNormalProfile() {
        assertThat(ok(service.rank("ALL", "terminal", 5, "  ")).data().profile()).isEqualTo("terminal");
        assertThat(ok(service.rank("ALL", "terminal", 5, null)).data().items())
                .extracting(i -> i.airport().iata())
                .isEqualTo(ok(service.rank("ALL", "terminal", 5)).data().items().stream().map(i -> i.airport().iata()).toList());
    }

    /** Checks that bad weights give a clear gap with the component names. */
    @Test
    void badWeightsGiveAClearGapWithTheComponentNames() {
        for (String bad : List.of("speed:0.5", "scale", "scale:abc", "scale:150%", "scale:-0.2", "scale:0:1")) {
            assertThat(service.rank("ALL", "terminal", 5, bad)).as(bad).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                    na -> assertThat(na.whatIsAvailable().toString()).contains("congestion", "scale"));
        }
    }

    /** Checks that the comparison accepts custom weights for a profile focus but not for congestion. */
    @Test
    void theComparisonAcceptsCustomWeightsForAProfileFocusButNotForCongestion() {
        ComparisonView view = ok(service.compare(List.of("BOS", "LAX"), "terminal", "scale:0")).data();

        assertThat(view.profile()).isEqualTo("custom");
        assertThat(service.compare(List.of("BOS", "LAX"), "congestion", "scale:0"))
                .isInstanceOf(QueryResult.NotAvailable.class);
    }

    /** Checks that the methodology lists the regions. */
    @Test
    void theMethodologyListsTheRegions() {
        assertThat(ok(service.methodology()).data().regions()).contains("NEW_ENGLAND", "WEST_COAST", "MIDWEST");
    }
}
