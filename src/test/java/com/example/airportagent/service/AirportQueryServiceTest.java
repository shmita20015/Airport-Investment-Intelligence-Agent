package com.example.airportagent.service;

import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.FaaStatusClient;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Contribution;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.LiveStatus;
import com.example.airportagent.domain.Region;
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

    @Test
    void newEnglandTerminalRankingStartsWithBostonAndStaysInTheRegion() {
        QueryResult.Ok<RankingView> result = ok(service.rank("NEW_ENGLAND", "terminal", 10));

        List<RankedItem> items = result.data().items();
        assertThat(items.get(0).airport().iata()).isEqualTo("BOS");
        assertThat(items).extracting(i -> i.airport().region()).containsOnly(Region.NEW_ENGLAND);
        assertThat(items).extracting(RankedItem::rank).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, items.size()).boxed().toList());
        assertThat(items).extracting(RankedItem::nationalRank).isSorted();
        assertThat(items).extracting(i -> i.score().value()).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(result.data().profile()).isEqualTo("terminal");
        assertThat(result.envelope().profile()).isEqualTo("terminal");
    }

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

    @Test
    void rankingStatesTheLimitsInTheEnvelope() {
        QueryResult.Ok<RankingView> result = ok(service.rank("NEW_ENGLAND", "terminal", 5));

        assertThat(result.envelope().assumptions()).anyMatch(text -> text.contains("heuristics"));
        assertThat(result.envelope().outOfScope()).anyMatch(text -> text.contains("cost, revenue or profit"));
        assertThat(result.envelope().parameters()).containsEntry("region", "NEW_ENGLAND").containsEntry("topN", "5");
        assertThat(result.envelope().sources()).isNotEmpty();
    }

    @Test
    void topNIsLimitedToTheAllowedRange() {
        assertThat(ok(service.rank("ALL", "general", 1000)).data().items()).hasSize(AirportQueryService.MAX_TOP_N);
        assertThat(ok(service.rank("ALL", "general", 0)).data().items()).hasSize(1);
        assertThat(ok(service.rank("ALL", null, null)).data().items()).hasSize(AirportQueryService.DEFAULT_TOP_N);
    }

    @Test
    void unknownProfileOrRegionGivesAClearGap() {
        assertThat(service.rank("NEW_ENGLAND", "nonsense", 5)).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                na -> assertThat(na.whatIsAvailable()).contains("terminal", "general"));
        assertThat(service.rank("MARS", "terminal", 5)).isInstanceOfSatisfying(QueryResult.NotAvailable.class,
                na -> assertThat(na.reason()).contains("Unknown region"));
    }

    // ---------------------------------------------------------------- one airport

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

    @Test
    void anUnknownAirportIsOutOfScope() {
        assertThat(service.airportProfile("ZZZ", "general")).isInstanceOfSatisfying(QueryResult.OutOfScope.class,
                out -> assertThat(out.scopeRule()).contains("Foreign airports are not in the data"));
        assertThat(service.airportProfile("LHR", "general")).isInstanceOf(QueryResult.OutOfScope.class);
    }

    // ---------------------------------------------------------------- comparison

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

    @Test
    void comparisonWithAnUnknownCodeIsPartial() {
        assertThat(service.compare(List.of("LAX", "ZZZ"), "congestion")).isInstanceOfSatisfying(
                QueryResult.Partial.class, partial -> assertThat(partial.missing().toString()).contains("ZZZ is not in the data"));
    }

    @Test
    void comparisonNeedsAtLeastTwoAirports() {
        assertThat(service.compare(List.of("LAX"), "congestion")).isInstanceOf(QueryResult.NotAvailable.class);
        assertThat(service.compare(null, "congestion")).isInstanceOf(QueryResult.NotAvailable.class);
    }

    // ---------------------------------------------------------------- long-haul share

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

    @Test
    void longHaulLimitIsRoundedDownAndTheAnswerSaysSo() {
        QueryResult.Ok<LongHaulView> result = ok(service.longHaul("ANC", 2600));

        assertThat(result.data().limitWasRounded()).isTrue();
        assertThat(result.data().limitUsedMiles()).isEqualTo(2500);
        assertThat(result.envelope().uncertainties()).anyMatch(text -> text.contains("rounded down to 2500"));
    }

    @Test
    void aLowerLimitGivesAHigherShare() {
        double at2500 = ok(service.longHaul("ANC", 2500)).data().sharePassengerOnly();
        double at1500 = ok(service.longHaul("ANC", 1500)).data().sharePassengerOnly();

        assertThat(at1500).isGreaterThan(at2500);
    }

    @Test
    void longHaulRejectsANegativeLimitAndUnknownAirports() {
        assertThat(service.longHaul("ANC", -5)).isInstanceOf(QueryResult.NotAvailable.class);
        assertThat(service.longHaul("ZZZ", 2500)).isInstanceOf(QueryResult.OutOfScope.class);
    }

    // ---------------------------------------------------------------- unmet demand

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

    @Test
    void airportsWithoutAnExpertNoteHaveNone() {
        assertThat(ok(service.unmetDemand("LAX")).data().expertNote()).isNull();
    }

    // ---------------------------------------------------------------- live status

    @Test
    void liveStatusComesFromTheFaaClient() {
        LIVE_FAILS.set(false);

        QueryResult.Ok<LiveStatusView> result = ok(service.liveStatus("SFO"));

        assertThat(result.data().status().updateTime()).isEqualTo("test time");
        assertThat(result.envelope().assumptions()).anyMatch(text -> text.contains("does not change any score"));
    }

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

    @Test
    void anAmbiguousNameIsNotResolvedAndListsTheOptions() {
        Resolution resolution = ok(service.resolveAirport("Portland")).data();

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.best()).isNull();
        assertThat(resolution.alternatives()).extracting(c -> c.airport().iata()).contains("PDX", "PWM");
    }

    @Test
    void unknownOrEmptyNamesAreOutOfScope() {
        assertThat(service.resolveAirport("Heathrow")).isInstanceOf(QueryResult.OutOfScope.class);
        assertThat(service.resolveAirport("  ")).isInstanceOf(QueryResult.OutOfScope.class);
        assertThat(service.resolveAirport(null)).isInstanceOf(QueryResult.OutOfScope.class);
    }

    @Test
    void theMethodologyShowsTheProfilesFromTheSettings() {
        Views.MethodologyView view = ok(service.methodology()).data();

        assertThat(view.weightProfiles()).containsKeys("terminal", "general");
        assertThat(view.weightProfiles().get("terminal").values().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(view.unmetDemandWeights()).containsKeys("load_factor", "growth", "congestion");
        assertThat(view.rankedAirports()).isEqualTo(233);
        assertThat(view.minEnplanements()).isEqualTo(100_000);
    }

    @Test
    void theToolsAcceptANameAndACode() {
        double byCode = ok(service.longHaul("ANC", 2500)).data().sharePassengerOnly();

        assertThat(ok(service.longHaul("Anchorage", 2500)).data().sharePassengerOnly()).isEqualTo(byCode);
        assertThat(ok(service.compare(List.of("LA", "Santa Ana"), "congestion")).data().airports())
                .extracting(a -> a.airport().iata()).containsExactly("LAX", "SNA");
    }

    @Test
    void aNameWithMoreThanOneAirportAsksTheCallerToChoose() {
        QueryResult<LongHaulView> result = service.longHaul("Portland", 2500);

        assertThat(result).isInstanceOfSatisfying(QueryResult.NotAvailable.class, na -> {
            assertThat(na.reason()).contains("more than one airport");
            assertThat(na.whatIsAvailable().toString()).contains("PWM (").contains("PDX (");
        });
    }

    private String resolved(String text) {
        Resolution resolution = ok(service.resolveAirport(text)).data();
        assertThat(resolution.resolved()).as(text).isTrue();
        return resolution.best().airport().iata();
    }
}
