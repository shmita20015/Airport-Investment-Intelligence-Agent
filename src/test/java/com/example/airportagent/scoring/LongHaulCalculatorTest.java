package com.example.airportagent.scoring;

import com.example.airportagent.scoring.LongHaulCalculator.Bucket;
import com.example.airportagent.scoring.LongHaulCalculator.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongHaulCalculatorTest {

    // Departures: 0-499 mi: 50 pax + 0 cargo. 500-999: 30 + 10. 2500-2999: 10 + 30. 3000+: 10 + 10.
    private static final List<Bucket> BUCKETS = List.of(
            new Bucket(0, 50, 0),
            new Bucket(500, 30, 10),
            new Bucket(2500, 10, 30),
            new Bucket(3000, 10, 10));

    @Test
    void passengerOnlyShareAtTheLimit() {
        Result r = LongHaulCalculator.share(BUCKETS, 2500, false);

        // long haul = 10 + 10 = 20. total = 50 + 30 + 10 + 10 = 100.
        assertThat(r.share().getAsDouble()).isEqualTo(0.20);
        assertThat(r.totalDepartures()).isEqualTo(100);
        assertThat(r.includesCargo()).isFalse();
    }

    @Test
    void shareWithCargoUsesAllDepartures() {
        Result r = LongHaulCalculator.share(BUCKETS, 2500, true);

        // long haul = (10 + 30) + (10 + 10) = 60. total = 50 + 40 + 40 + 20 = 150.
        assertThat(r.share().getAsDouble()).isEqualTo(0.40);
    }

    @Test
    void roundsTheLimitDownToTheBucketEdgeAndSaysSo() {
        Result r = LongHaulCalculator.share(BUCKETS, 2600, false);

        assertThat(r.limitUsed()).isEqualTo(2500);
        assertThat(r.limitRequested()).isEqualTo(2600);
        assertThat(r.limitWasRounded()).isTrue();
    }

    @Test
    void lowerLimitGivesAHigherOrEqualShare() {
        double high = LongHaulCalculator.share(BUCKETS, 2500, false).share().getAsDouble();
        double low = LongHaulCalculator.share(BUCKETS, 500, false).share().getAsDouble();

        assertThat(low).isGreaterThanOrEqualTo(high);
    }

    @Test
    void emptyWhenThereAreNoDepartures() {
        assertThat(LongHaulCalculator.share(List.of(), 2500, false).share()).isEmpty();
    }

    @Test
    void rejectsNegativeLimit() {
        assertThatThrownBy(() -> LongHaulCalculator.share(BUCKETS, -1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
