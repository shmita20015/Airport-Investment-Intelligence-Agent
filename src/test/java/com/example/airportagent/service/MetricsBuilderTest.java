package com.example.airportagent.service;

import com.example.airportagent.config.ScoringProperties;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.Rows.BucketRow;
import com.example.airportagent.data.Rows.DatasetInfo;
import com.example.airportagent.data.Rows.EnplanementRow;
import com.example.airportagent.data.Rows.OntimeRow;
import com.example.airportagent.data.Rows.TrafficRow;
import com.example.airportagent.domain.Airport;
import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.HubClass;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.MetricValue;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsBuilderTest {

    private static final Offset<Double> TINY = Offset.offset(1e-9);

    private final MetricsBuilder builder =
            new MetricsBuilder(new ScoringProperties(Map.of(), Map.of(), 100_000, 2_000, 2_500, Map.of()));

    private static Airport airport(String code, int runways) {
        return new Airport(code, code, code + " Airport", "City", "MA", runways,
                HubClass.MEDIUM);
    }

    /** An airport with a complete set of facts. All expected values are calculated by hand. */
    private AirportDataset fullDataset() {
        Airport xxx = airport("XXX", 4);
        return new AirportDataset(
                Map.of("XXX", xxx),
                Map.of("XXX", new TrafficRow("XXX", 8_000_000, 10_000_000, 100_000, 5_000)),
                Map.of("XXX", List.of(new BucketRow("XXX", 0, 60_000, 5_000), new BucketRow("XXX", 2500, 40_000, 0))),
                Map.of("XXX", new OntimeRow("XXX", 50_000, OptionalDouble.of(0.2), OptionalDouble.of(0.01),
                        OptionalDouble.of(15))),
                Map.of("XXX", new EnplanementRow("XXX", OptionalLong.of(4_000_000),
                        OptionalLong.of(6_250_000))),
                Map.of(), new DatasetInfo(2025, "test"));
    }

    /** Checks that calculates kpis from facts. */
    @Test
    void calculatesKpisFromFacts() {
        AirportDataset data = fullDataset();

        AirportMetrics m = builder.build(data, data.airports().get("XXX"), 2500);

        assertThat(m.kpi(Kpi.LOAD_FACTOR).number().getAsDouble()).isCloseTo(0.8, TINY);
        assertThat(m.kpi(Kpi.PAX_PER_DEPARTURE).number().getAsDouble()).isCloseTo(80.0, TINY);
        assertThat(m.kpi(Kpi.OPS_PER_RUNWAY).number().getAsDouble()).isCloseTo(50_000.0, TINY); // 2 x 100000 / 4
        assertThat(m.kpi(Kpi.GROWTH).number().getAsDouble()).isCloseTo(0.25, TINY); // sqrt(6.25 / 4) - 1
        assertThat(m.kpi(Kpi.DELAY15_RATE).number().getAsDouble()).isCloseTo(0.2, TINY);
        assertThat(m.kpi(Kpi.TAXI_OUT_MIN).number().getAsDouble()).isCloseTo(15.0, TINY);
        assertThat(m.passengers()).isEqualTo(6_250_000);
        assertThat(m.kpi(Kpi.ENPLANEMENTS).number().getAsDouble()).isCloseTo(6_250_000.0, TINY);
        assertThat(m.kpi(Kpi.ENPLANEMENTS)).isInstanceOf(MetricValue.Direct.class);
    }

    /** Checks that long haul share follows the limit and the cargo choice. */
    @Test
    void longHaulShareFollowsTheLimitAndTheCargoChoice() {
        AirportDataset data = fullDataset();
        Airport xxx = data.airports().get("XXX");

        // Passenger only: 40,000 / 100,000. With cargo: 40,000 / 105,000.
        assertThat(builder.build(data, xxx, 2500).kpi(Kpi.LONG_HAUL_SHARE).number().getAsDouble())
                .isCloseTo(0.4, TINY);
        assertThat(builder.longHaulResult(data, "XXX", 2500, true).share().getAsDouble())
                .isCloseTo(40_000.0 / 105_000.0, TINY);
    }

    /** Checks that a tiny long haul share is set to zero in the kpi but not in the tool. */
    @Test
    void aTinyLongHaulShareIsSetToZeroInTheKpiButNotInTheTool() {
        Airport xxx = airport("XXX", 2);
        // 3 of 50,000 departures are long haul (0.006 percent). This is noise.
        AirportDataset data = new AirportDataset(Map.of("XXX", xxx), Map.of(),
                Map.of("XXX", List.of(new BucketRow("XXX", 0, 49_997, 0), new BucketRow("XXX", 2500, 3, 0))),
                Map.of(), Map.of(), Map.of(), new DatasetInfo(2025, "test"));

        MetricValue kpi = builder.build(data, xxx, 2500).kpi(Kpi.LONG_HAUL_SHARE);

        assertThat(kpi.number().getAsDouble()).isZero();
        assertThat(builder.longHaulResult(data, "XXX", 2500, false).share().getAsDouble())
                .isCloseTo(3.0 / 50_000, TINY);
    }

    /** Checks that proxies are labeled as proxies. */
    @Test
    void proxiesAreLabeledAsProxies() {
        AirportDataset data = fullDataset();

        AirportMetrics m = builder.build(data, data.airports().get("XXX"), 2500);

        assertThat(m.kpi(Kpi.LOAD_FACTOR)).isInstanceOf(MetricValue.Proxy.class);
        assertThat(m.kpi(Kpi.OPS_PER_RUNWAY)).isInstanceOf(MetricValue.Proxy.class);
        assertThat(m.kpi(Kpi.GROWTH)).isInstanceOf(MetricValue.Direct.class);
        assertThat(m.kpi(Kpi.DELAY15_RATE)).isInstanceOf(MetricValue.Direct.class);
    }

    /** Checks that an airport without facts has missing kpis with reasons. */
    @Test
    void anAirportWithoutFactsHasMissingKpisWithReasons() {
        Airport yyy = airport("YYY", 0);
        AirportDataset data = new AirportDataset(Map.of("YYY", yyy), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), new DatasetInfo(2025, "test"));

        AirportMetrics m = builder.build(data, yyy, 2500);

        for (Kpi kpi : Kpi.values()) {
            assertThat(m.kpi(kpi)).as(kpi.name()).isInstanceOf(MetricValue.Missing.class);
        }
        assertThat(m.passengers()).isZero();
    }

    /** Checks that on time kpis are missing when there are too few flights. */
    @Test
    void onTimeKpisAreMissingWhenThereAreTooFewFlights() {
        Airport zzz = airport("ZZZ", 2);
        AirportDataset data = new AirportDataset(Map.of("ZZZ", zzz), Map.of(), Map.of(),
                Map.of("ZZZ", new OntimeRow("ZZZ", 500, OptionalDouble.of(0.5), OptionalDouble.of(0.1),
                        OptionalDouble.of(30))),
                Map.of(), Map.of(), new DatasetInfo(2025, "test"));

        MetricValue delay = builder.build(data, zzz, 2500).kpi(Kpi.DELAY15_RATE);

        assertThat(delay).isInstanceOfSatisfying(MetricValue.Missing.class,
                missing -> assertThat(missing.reason()).contains("fewer than 2000"));
    }

    /** Checks that missing runway count gives no runway kpi and not zero. */
    @Test
    void missingRunwayCountGivesNoRunwayKpiAndNotZero() {
        AirportDataset data = fullDataset();
        Airport noRunways = airport("XXX", 0);

        MetricValue ops = builder.build(data, noRunways, 2500).kpi(Kpi.OPS_PER_RUNWAY);

        assertThat(ops).isInstanceOf(MetricValue.Missing.class);
    }
}
