package com.example.airportagent.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolLogTest {

    /** Checks that a turn allows only the maximum number of calls. */
    @Test
    void aTurnAllowsOnlyTheMaximumNumberOfCalls() {
        ToolLog log = new ToolLog();
        log.startTurn();

        for (int i = 0; i < ToolLog.MAX_CALLS_PER_TURN; i++) {
            assertThat(log.allowCall()).as("call " + (i + 1)).isTrue();
        }
        assertThat(log.allowCall()).isFalse();
    }

    /** Checks that a new turn resets the call count. */
    @Test
    void aNewTurnResetsTheCallCount() {
        ToolLog log = new ToolLog();
        log.startTurn();
        for (int i = 0; i <= ToolLog.MAX_CALLS_PER_TURN; i++) {
            log.allowCall();
        }

        log.startTurn();

        assertThat(log.allowCall()).isTrue();
    }

    /** Checks that the results of the last turn stay available for a follow up. */
    @Test
    void theResultsOfTheLastTurnStayAvailableForAFollowUp() {
        ToolLog log = new ToolLog();
        log.startTurn();
        log.record("tool", "{\"a\":1}");
        assertThat(log.turnCalls()).extracting(ToolLog.Call::tool).containsExactly("tool");

        log.startTurn();

        assertThat(log.turnCalls()).isEmpty();
        assertThat(log.allJson()).containsExactly("{\"a\":1}");
    }

    /** Checks that only the newest earlier results are kept. */
    @Test
    void onlyTheNewestEarlierResultsAreKept() {
        ToolLog log = new ToolLog();
        for (int turn = 0; turn < 50; turn++) {
            log.startTurn();
            log.record("tool", "{\"turn\":" + turn + "}");
        }

        assertThat(log.allJson()).hasSizeLessThanOrEqualTo(31);
        assertThat(log.allJson()).contains("{\"turn\":49}").doesNotContain("{\"turn\":0}");
    }
}
