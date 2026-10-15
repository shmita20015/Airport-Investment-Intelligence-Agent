package com.example.airportagent.scoring;

import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Component;
import com.example.airportagent.domain.Confidence;
import com.example.airportagent.domain.Contribution;
import com.example.airportagent.domain.Kpi;
import com.example.airportagent.domain.MetricValue;
import com.example.airportagent.domain.Score;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.airportagent.scoring.TestData.airport;
import static com.example.airportagent.scoring.TestData.profile;
import static org.assertj.core.api.Assertions.assertThat;

class ScoreCalculatorTest {

    private static final Offset<Double> TINY = Offset.offset(1e-9);

    private final ScoreCalculator calculator = new ScoreCalculator();

    private final WeightProfile growthAndLoad =
            profile("growth-load", Component.GROWTH, 0.5, Component.LOAD_FACTOR, 0.5);

    // Growth percentiles (N=3): A 16.667, B 50, C 83.333. Load percentiles: A 83.333, B 16.667, C 50.
    private final AirportMetrics a = airport("A", 100, Kpi.GROWTH, 1, Kpi.LOAD_FACTOR, 0.9);
    private final AirportMetrics b = airport("B", 100, Kpi.GROWTH, 2, Kpi.LOAD_FACTOR, 0.7);
    private final AirportMetrics c = airport("C", 100, Kpi.GROWTH, 3, Kpi.LOAD_FACTOR, 0.8);

    /** Checks that scores match values calculated by hand. */
    @Test
    void scoresMatchValuesCalculatedByHand() {
        List<Score> ranked = calculator.rank(List.of(a, b, c), growthAndLoad, 10);

        // A: 0.5 x 16.667 + 0.5 x 83.333 = 50. B: 0.5 x 50 + 0.5 x 16.667 = 33.333. C: 0.5 x 83.333 + 0.5 x 50 = 66.667.
        assertThat(ranked).extracting(Score::iata).containsExactly("C", "A", "B");
        assertThat(ranked.get(0).value()).isCloseTo(66.6666666667, Offset.offset(1e-6));
        assertThat(ranked.get(1).value()).isCloseTo(50.0, TINY);
        assertThat(ranked.get(2).value()).isCloseTo(33.3333333333, Offset.offset(1e-6));
    }

    /** Checks that contributions add up to the score and are sorted by points. */
    @Test
    void contributionsAddUpToTheScoreAndAreSortedByPoints() {
        Score score = calculator.rank(List.of(a, b, c), growthAndLoad, 10).get(1); // A

        double sum = score.contributions().stream().mapToDouble(Contribution::points).sum();
        assertThat(sum).isCloseTo(score.value(), TINY);
        assertThat(score.contributions()).extracting(Contribution::points).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
    }

    /** Checks that higher kpi never gives a lower score. */
    @Test
    void higherKpiNeverGivesALowerScore() {
        AirportMetrics p1 = airport("P1", 1, Kpi.GROWTH, 2, Kpi.LOAD_FACTOR, 0.7);
        AirportMetrics p2 = airport("P2", 1, Kpi.GROWTH, 4, Kpi.LOAD_FACTOR, 0.8);
        AirportMetrics p3 = airport("P3", 1, Kpi.GROWTH, 6, Kpi.LOAD_FACTOR, 0.9);

        double previous = -1;
        for (double growth = 0; growth <= 8; growth += 0.5) {
            AirportMetrics x = airport("X", 1, Kpi.GROWTH, growth, Kpi.LOAD_FACTOR, 0.75);
            List<AirportMetrics> set = List.of(p1, p2, p3, x);
            double score = calculator.score(x, growthAndLoad, Percentiles.of(set)).orElseThrow().value();
            assertThat(score).isGreaterThanOrEqualTo(previous);
            previous = score;
        }
    }

    /** Checks that missing component is removed and not scored as zero. */
    @Test
    void missingComponentIsRemovedAndNotScoredAsZero() {
        AirportMetrics d = airport("D", 100, Kpi.GROWTH, 4); // no load factor
        List<AirportMetrics> set = List.of(a, b, c, d);

        Score score = calculator.score(d, growthAndLoad, Percentiles.of(set)).orElseThrow();

        // Growth percentiles (N=4): D is the highest, so 87.5. The score is the growth component alone.
        assertThat(score.value()).isCloseTo(87.5, TINY);
        assertThat(score.coverage()).isCloseTo(0.5, TINY);
        assertThat(score.confidence()).isEqualTo(Confidence.LOW);
        assertThat(score.missingComponents()).containsExactly(Component.LOAD_FACTOR);
        assertThat(score.contributions()).singleElement()
                .satisfies(contribution -> assertThat(contribution.weight()).isCloseTo(1.0, TINY));
    }

    /** Checks that a missing kpi inside a component lowers the coverage. */
    @Test
    void aMissingKpiInsideAComponentLowersTheCoverage() {
        // Congestion has 4 KPIs. X has only 1 of them (the runway KPI), so 3 of 4 KPIs are missing.
        WeightProfile congestionAndGrowth = profile("cg", Component.CONGESTION, 0.5, Component.GROWTH, 0.5);
        AirportMetrics x = airport("X", 1, Kpi.OPS_PER_RUNWAY, 100, Kpi.GROWTH, 0.1);
        AirportMetrics y = airport("Y", 1, Kpi.OPS_PER_RUNWAY, 200, Kpi.DELAY15_RATE, 0.2, Kpi.TAXI_OUT_MIN, 20,
                Kpi.CANCEL_RATE, 0.01, Kpi.GROWTH, 0.2);

        Percentiles peers = Percentiles.of(List.of(x, y));
        Score sx = calculator.score(x, congestionAndGrowth, peers).orElseThrow();
        Score sy = calculator.score(y, congestionAndGrowth, peers).orElseThrow();

        // X: congestion 1 of 4 KPIs (0.5 x 0.25) + growth 1 of 1 (0.5) = 0.625.
        assertThat(sx.coverage()).isCloseTo(0.625, TINY);
        assertThat(sx.confidence()).isEqualTo(Confidence.LOW);
        assertThat(sx.missingKpis()).containsExactly(Kpi.DELAY15_RATE, Kpi.TAXI_OUT_MIN, Kpi.CANCEL_RATE);
        assertThat(sx.missingComponents()).isEmpty();
        assertThat(sy.coverage()).isCloseTo(1.0, TINY);
        assertThat(sy.missingKpis()).isEmpty();
    }

    /** Checks that no data gives no score. */
    @Test
    void noDataGivesNoScore() {
        AirportMetrics empty = airport("E", 100);

        assertThat(calculator.score(empty, growthAndLoad, Percentiles.of(List.of(a, b, c, empty)))).isEmpty();
        assertThat(calculator.rank(List.of(a, empty), growthAndLoad, 10)).extracting(Score::iata).containsExactly("A");
    }

    /** Checks that congestion is the mean of its available kpi percentiles. */
    @Test
    void congestionIsTheMeanOfItsAvailableKpiPercentiles() {
        // Delay percentiles (N=3): 16.667, 50, 83.333. Taxi percentiles: 50, 16.667, 83.333.
        AirportMetrics x = airport("X", 1, Kpi.DELAY15_RATE, 0.1, Kpi.TAXI_OUT_MIN, 20);
        AirportMetrics y = airport("Y", 1, Kpi.DELAY15_RATE, 0.2, Kpi.TAXI_OUT_MIN, 10);
        AirportMetrics z = airport("Z", 1, Kpi.DELAY15_RATE, 0.3, Kpi.TAXI_OUT_MIN, 30);
        Percentiles peers = Percentiles.of(List.of(x, y, z));

        Map<Component, Double> xv = calculator.componentValues(x, peers);
        Map<Component, Double> zv = calculator.componentValues(z, peers);

        assertThat(xv.get(Component.CONGESTION)).isCloseTo(33.3333333333, Offset.offset(1e-6));
        assertThat(zv.get(Component.CONGESTION)).isCloseTo(83.3333333333, Offset.offset(1e-6));
        assertThat(xv).doesNotContainKey(Component.GROWTH);
    }

    /** Checks that equal scores are ordered by passengers then by code. */
    @Test
    void equalScoresAreOrderedByPassengersThenByCode() {
        AirportMetrics x = airport("XXX", 200, Kpi.GROWTH, 1);
        AirportMetrics z = airport("ZZZ", 100, Kpi.GROWTH, 1);
        AirportMetrics y = airport("YYY", 100, Kpi.GROWTH, 1);
        WeightProfile growthOnly = profile("growth", Component.GROWTH, 1.0);

        List<Score> ranked = calculator.rank(List.of(z, y, x), growthOnly, 10);

        assertThat(ranked).extracting(Score::iata).containsExactly("XXX", "YYY", "ZZZ");
    }

    /** Checks that proxy kpi is marked in the trace. */
    @Test
    void proxyKpiIsMarkedInTheTrace() {
        Map<Kpi, MetricValue> kpis = Map.of(
                Kpi.LOAD_FACTOR, new MetricValue.Proxy(0.8, "T-100", "terminal pressure"),
                Kpi.GROWTH, new MetricValue.Direct(0.1, "FAA"));
        AirportMetrics x = new AirportMetrics("X", 1, kpis);

        Score score = calculator.score(x, growthAndLoad, Percentiles.of(List.of(x))).orElseThrow();

        assertThat(score.contributions())
                .filteredOn(contribution -> contribution.component() == Component.LOAD_FACTOR)
                .singleElement().satisfies(contribution -> assertThat(contribution.usesProxy()).isTrue());
        assertThat(score.contributions())
                .filteredOn(contribution -> contribution.component() == Component.GROWTH)
                .singleElement().satisfies(contribution -> assertThat(contribution.usesProxy()).isFalse());
    }

    /**
     * A small airport with a huge growth rate from a small base must not rank first.
     * With raw values, the wide growth range can dominate the score.
     * With percentiles, a large and congested airport ranks first.
     */
    @Test
    void smallAirportWithHugeGrowthDoesNotOutrankALargeCongestedAirport() {
        WeightProfile terminal = profile("terminal",
                Component.CONGESTION, 0.30, Component.GROWTH, 0.25, Component.LOAD_FACTOR, 0.20,
                Component.TERMINAL_STRAIN, 0.15, Component.LONG_HAUL, 0.10);

        AirportMetrics hvn = airport("HVN", 100_000, Kpi.GROWTH, 5.0, Kpi.DELAY15_RATE, 0.05,
                Kpi.LOAD_FACTOR, 0.60, Kpi.PAX_PER_DEPARTURE, 50, Kpi.LONG_HAUL_SHARE, 0.00);
        AirportMetrics bos = airport("BOS", 20_000_000, Kpi.GROWTH, 0.10, Kpi.DELAY15_RATE, 0.25,
                Kpi.LOAD_FACTOR, 0.85, Kpi.PAX_PER_DEPARTURE, 100, Kpi.LONG_HAUL_SHARE, 0.20);
        AirportMetrics bdl = airport("BDL", 3_000_000, Kpi.GROWTH, 0.08, Kpi.DELAY15_RATE, 0.15,
                Kpi.LOAD_FACTOR, 0.80, Kpi.PAX_PER_DEPARTURE, 80, Kpi.LONG_HAUL_SHARE, 0.05);
        AirportMetrics pvd = airport("PVD", 2_000_000, Kpi.GROWTH, 0.12, Kpi.DELAY15_RATE, 0.12,
                Kpi.LOAD_FACTOR, 0.78, Kpi.PAX_PER_DEPARTURE, 70, Kpi.LONG_HAUL_SHARE, 0.02);

        List<Score> ranked = calculator.rank(new ArrayList<>(List.of(hvn, bos, bdl, pvd)), terminal, 10);

        // Hand calculation (percentiles N=4: 12.5, 37.5, 62.5, 87.5): BOS 75.0, BDL 50.0, PVD 43.75, HVN 31.25.
        assertThat(ranked).extracting(Score::iata).containsExactly("BOS", "BDL", "PVD", "HVN");
        assertThat(ranked.get(0).value()).isCloseTo(75.0, TINY);
        assertThat(ranked.get(3).value()).isCloseTo(31.25, TINY);
    }

    /** Checks that the scale component lets a larger airport outrank a smaller one with faster growth. */
    @Test
    void theScaleComponentLetsALargerAirportOutrankASmallerOneWithFasterGrowth() {
        WeightProfile growthAndScale = profile("gs", Component.GROWTH, 0.4, Component.SCALE, 0.6);
        // Growth percentiles (N=3): A 83.333, B 50, C 16.667. Scale percentiles: A 16.667, B 50, C 83.333.
        AirportMetrics small = airport("AAA", 100_000, Kpi.GROWTH, 0.30, Kpi.ENPLANEMENTS, 100_000);
        AirportMetrics medium = airport("BBB", 200_000, Kpi.GROWTH, 0.20, Kpi.ENPLANEMENTS, 200_000);
        AirportMetrics large = airport("CCC", 300_000, Kpi.GROWTH, 0.10, Kpi.ENPLANEMENTS, 300_000);

        List<Score> ranked = calculator.rank(List.of(small, medium, large), growthAndScale, 10);

        // A: 0.4 x 83.333 + 0.6 x 16.667 = 43.333. B: 0.4 x 50 + 0.6 x 50 = 50. C: 0.4 x 16.667 + 0.6 x 83.333 = 56.667.
        assertThat(ranked).extracting(Score::iata).containsExactly("CCC", "BBB", "AAA");
        assertThat(ranked.get(0).value()).isCloseTo(56.6666666667, Offset.offset(1e-6));
        assertThat(ranked.get(1).value()).isCloseTo(50.0, TINY);
        assertThat(ranked.get(2).value()).isCloseTo(43.3333333333, Offset.offset(1e-6));
    }

    /** Checks that confidence bands follow coverage. */
    @Test
    void confidenceBandsFollowCoverage() {
        assertThat(Confidence.fromCoverage(1.0)).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.fromCoverage(0.9)).isEqualTo(Confidence.HIGH);
        assertThat(Confidence.fromCoverage(0.89)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.fromCoverage(0.7)).isEqualTo(Confidence.MEDIUM);
        assertThat(Confidence.fromCoverage(0.69)).isEqualTo(Confidence.LOW);
    }
}
