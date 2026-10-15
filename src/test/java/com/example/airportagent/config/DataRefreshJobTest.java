package com.example.airportagent.config;

import com.example.airportagent.data.AirportDataLoader;
import com.example.airportagent.data.AirportDataset;
import com.example.airportagent.data.Rows;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DataRefreshJobTest {

    /** Checks that the job tries again after a failure and stops at the first success. */
    @Test
    void theJobTriesAgainAfterAFailureAndStopsAtTheFirstSuccess() {
        AtomicInteger calls = new AtomicInteger();

        boolean done = DataRefreshJob.runWithRetry(3, 0, () -> calls.incrementAndGet() < 3 ? 1 : 0);

        assertThat(done).isTrue();
        assertThat(calls).hasValue(3);
    }

    /** Checks that the job gives up after the maximum number of tries. */
    @Test
    void theJobGivesUpAfterTheMaximumNumberOfTries() {
        AtomicInteger calls = new AtomicInteger();

        boolean done = DataRefreshJob.runWithRetry(3, 0, () -> {
            calls.incrementAndGet();
            return 1;
        });

        assertThat(done).isFalse();
        assertThat(calls).hasValue(3);
    }

    /** Checks that the real data pass the basic check. */
    @Test
    void theRealDataPassTheBasicCheck() {
        assertThat(DataRefreshJob.looksComplete(new AirportDataLoader().load())).isTrue();
    }

    /** Checks that empty data fail the basic check. */
    @Test
    void emptyDataFailTheBasicCheck() {
        AirportDataset empty = new AirportDataset(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                new Rows.DatasetInfo(2025, "now"));

        assertThat(DataRefreshJob.looksComplete(empty)).isFalse();
    }
}
