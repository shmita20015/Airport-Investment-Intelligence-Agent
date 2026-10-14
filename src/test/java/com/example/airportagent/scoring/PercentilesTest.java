package com.example.airportagent.scoring;

import com.example.airportagent.domain.AirportMetrics;
import com.example.airportagent.domain.Kpi;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.airportagent.scoring.TestData.airport;
import static org.assertj.core.api.Assertions.assertThat;

class PercentilesTest {

    @Test
    void ranksValuesWithTheMidRankFormula() {
        // values 10, 20, 30, 40: rank(x) = 100 x (below + 0.5 x equal) / 4
        Percentiles p = Percentiles.of(List.of(
                airport("A", 1, Kpi.GROWTH, 10),
                airport("B", 1, Kpi.GROWTH, 20),
                airport("C", 1, Kpi.GROWTH, 30),
                airport("D", 1, Kpi.GROWTH, 40)));

        assertThat(p.rank(Kpi.GROWTH, 10).getAsDouble()).isEqualTo(12.5);
        assertThat(p.rank(Kpi.GROWTH, 30).getAsDouble()).isEqualTo(62.5);
        assertThat(p.rank(Kpi.GROWTH, 40).getAsDouble()).isEqualTo(87.5);
    }

    @Test
    void valueBetweenPeersHasNoEqualPart() {
        Percentiles p = Percentiles.of(List.of(
                airport("A", 1, Kpi.GROWTH, 10),
                airport("B", 1, Kpi.GROWTH, 20),
                airport("C", 1, Kpi.GROWTH, 30),
                airport("D", 1, Kpi.GROWTH, 40)));

        assertThat(p.rank(Kpi.GROWTH, 25).getAsDouble()).isEqualTo(50.0);
    }

    @Test
    void equalValuesGetTheSamePercentile() {
        Percentiles p = Percentiles.of(List.of(
                airport("A", 1, Kpi.GROWTH, 10),
                airport("B", 1, Kpi.GROWTH, 10),
                airport("C", 1, Kpi.GROWTH, 20)));

        // below = 0, equal = 2, N = 3: 100 x 1 / 3
        assertThat(p.rank(Kpi.GROWTH, 10).getAsDouble()).isCloseTo(33.3333, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void missingValuesAreNotInThePeerSet() {
        AirportMetrics noGrowth = airport("C", 1, Kpi.LOAD_FACTOR, 0.8);
        Percentiles p = Percentiles.of(List.of(
                airport("A", 1, Kpi.GROWTH, 10),
                airport("B", 1, Kpi.GROWTH, 20),
                noGrowth));

        assertThat(p.peerCount(Kpi.GROWTH)).isEqualTo(2);
        assertThat(p.rank(Kpi.GROWTH, 20).getAsDouble()).isEqualTo(75.0);
    }

    @Test
    void emptyWhenNoPeerHasTheKpi() {
        Percentiles p = Percentiles.of(List.of(airport("A", 1, Kpi.GROWTH, 10)));

        assertThat(p.rank(Kpi.TAXI_OUT_MIN, 5)).isEmpty();
    }
}
