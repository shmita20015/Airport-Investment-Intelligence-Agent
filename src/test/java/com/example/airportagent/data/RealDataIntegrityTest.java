package com.example.airportagent.data;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.HubClass;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.MetricValue;
import com.example.airportagent.scoring.LongHaulCalculator;
import com.example.airportagent.service.MetricsBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the real data files (src/main/resources/data). A gap here would break an example question.
 * The expected values come from the data checks in docs/DESIGN-full.md, section 21.
 */
class RealDataIntegrityTest {

    private static final long MIN_ENPLANEMENTS = 100_000;
    private static final List<String> KEY_AIRPORTS = List.of("SFO", "LAX", "SNA", "ANC");

    private static AirportDataset data;
    private static MetricsBuilder builder;

    @BeforeAll
    static void load() {
        data = new AirportDataLoader().load();
        builder = new MetricsBuilder(new ScoringProperties(Map.of(), Map.of(), MIN_ENPLANEMENTS, 1_000, 2_500, Map.of()));
    }

    @Test
    void theDataSetHasTheExpectedYearAndSize() {
        assertThat(data.info().dataYear()).isEqualTo(2025);
        assertThat(data.airports()).hasSizeGreaterThan(500);
        assertThat(rankedAirports()).hasSizeBetween(150, 300);
    }

    @Test
    void everyRowOfEveryFileBelongsToAKnownAirport() {
        assertThat(data.traffic().keySet()).isSubsetOf(data.airports().keySet());
        assertThat(data.buckets().keySet()).isSubsetOf(data.airports().keySet());
        assertThat(data.ontime().keySet()).isSubsetOf(data.airports().keySet());
        assertThat(data.enplanements().keySet()).isSubsetOf(data.airports().keySet());
    }

    @Test
    void theAirportsOfTheExampleQuestionsHaveAllTheFacts() {
        for (String code : KEY_AIRPORTS) {
            Airport airport = data.airport(code).orElseThrow(() -> new AssertionError(code + " is missing"));
            AirportMetrics metrics = builder.build(data, airport, 2_500);
            for (Kpi kpi : Kpi.values()) {
                assertThat(metrics.kpi(kpi)).as(code + " " + kpi).isNotInstanceOf(MetricValue.Missing.class);
            }
        }
    }

    @Test
    void newEnglandAirportsAboveTheSizeLimitHaveTrafficRunwaysAndGrowth() {
        List<Airport> newEngland = rankedAirports().stream()
                .filter(airport -> java.util.Set.of("CT", "MA", "ME", "NH", "RI", "VT").contains(airport.state())).toList();

        assertThat(newEngland).extracting(Airport::iata).contains("BOS", "BDL", "PVD", "PWM", "BTV", "MHT", "BGR");
        for (Airport airport : newEngland) {
            AirportMetrics metrics = builder.build(data, airport, 2_500);
            assertThat(airport.runwayCount()).as(airport.iata() + " runways").isPositive();
            for (Kpi kpi : List.of(Kpi.GROWTH, Kpi.LOAD_FACTOR, Kpi.PAX_PER_DEPARTURE, Kpi.OPS_PER_RUNWAY,
                    Kpi.LONG_HAUL_SHARE)) {
                assertThat(metrics.kpi(kpi)).as(airport.iata() + " " + kpi).isNotInstanceOf(MetricValue.Missing.class);
            }
        }
    }

    @Test
    void newHavenHasTrafficButNoDelayData() {
        Airport hvn = data.airport("HVN").orElseThrow();

        AirportMetrics metrics = builder.build(data, hvn, 2_500);

        assertThat(metrics.kpi(Kpi.LOAD_FACTOR)).isNotInstanceOf(MetricValue.Missing.class);
        assertThat(metrics.kpi(Kpi.DELAY15_RATE)).isInstanceOf(MetricValue.Missing.class);
        assertThat(metrics.kpi(Kpi.TAXI_OUT_MIN)).isInstanceOf(MetricValue.Missing.class);
    }

    @Test
    void anchorageLongHaulShareMatchesTheCheckedValues() {
        // Checked on 2026-10-14 with the combined T-100 table, passenger flights, 2,500 miles: 14.9 percent.
        LongHaulCalculator.Result passenger = builder.longHaulResult(data, "ANC", 2_500, false);
        LongHaulCalculator.Result withCargo = builder.longHaulResult(data, "ANC", 2_500, true);

        assertThat(passenger.share().getAsDouble()).isBetween(0.14, 0.16);
        assertThat(passenger.totalDepartures()).isEqualTo(40_387);
        // Cargo flights are a large part of the Anchorage departures. The share changes a lot.
        assertThat(withCargo.totalDepartures()).isGreaterThan(passenger.totalDepartures() * 1.5);
        assertThat(withCargo.share().getAsDouble()).isNotEqualTo(passenger.share().getAsDouble());
    }

    @Test
    void sanFranciscoLongHaulShareMatchesTheCheckedValue() {
        // Checked on 2026-10-14: 18.4 percent (combined table, passenger flights, 2,500 miles).
        assertThat(builder.longHaulResult(data, "SFO", 2_500, false).share().getAsDouble()).isBetween(0.17, 0.20);
    }

    @Test
    void hubClassesAndStatesAreRead() {
        assertThat(data.airport("LAX").orElseThrow().hubClass()).isEqualTo(HubClass.LARGE);
        assertThat(data.airport("SNA").orElseThrow().hubClass()).isEqualTo(HubClass.MEDIUM);
        assertThat(data.airport("BOS").orElseThrow().state()).isEqualTo("MA");
        assertThat(data.airport("SFO").orElseThrow().state()).isEqualTo("CA");
    }

    @Test
    void aliasesResolveTheNamesOfTheExampleQuestions() {
        assertThat(data.aliases()).containsEntry("la", "LAX").containsEntry("santa ana", "SNA")
                .containsEntry("palm beach", "PBI");
        assertThat(data.aliases().values()).allSatisfy(code -> assertThat(data.airports()).containsKey(code));
    }

    @Test
    void westPalmBeachHasOneRecordWithTheBtsCode() {
        // OurAirports and the FAA CY2025 file say DJT. BTS and the FAA CY2024 file say PBI. The app uses PBI.
        assertThat(data.airports()).containsKey("PBI").doesNotContainKey("DJT");
        assertThat(data.traffic()).containsKey("PBI");
        assertThat(data.enplanements().get("PBI").cy23()).isPresent();
        assertThat(data.enplanements().get("PBI").cy25()).isPresent();
    }

    @Test
    void foreignAirportsAreNotInTheData() {
        // Foreign airports use codes like these. A code match with a small US airfield must not add them.
        assertThat(data.airports()).doesNotContainKeys("CUN", "LHR", "PUJ", "HND", "DUB", "PVG");
    }

    private static List<Airport> rankedAirports() {
        return data.airports().values().stream()
                .filter(airport -> data.enplanements().get(airport.iata()) != null
                        && data.enplanements().get(airport.iata()).cy25().orElse(0) >= MIN_ENPLANEMENTS)
                .collect(Collectors.toList());
    }
}
