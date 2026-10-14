package com.example.airportagent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NumberCheckTest {

    private static final String TOOL_JSON = "{\"data\":{\"sharePassengerOnly\":0.14912,\"score\":66.97,"
            + "\"enplanements\":40956673,\"nationalRank\":12,\"year\":2025}}";

    private static List<String> unsupported(String answer) {
        return NumberCheck.unsupported(answer, List.of(TOOL_JSON));
    }

    @Test
    void aShareCanBeShownAsARoundedPercentage() {
        assertThat(unsupported("The long-haul share is 14.9%.")).isEmpty();
    }

    @Test
    void aValueCanBeRounded() {
        assertThat(unsupported("The score is 67.0 and the rank is 12.")).isEmpty();
        assertThat(unsupported("The score is 67.")).isEmpty();
    }

    @Test
    void aLargeNumberCanBeShownWithCommasOrWords() {
        assertThat(unsupported("It had 40,956,673 enplanements.")).isEmpty();
        assertThat(unsupported("It had about 41 million enplanements.")).isEmpty();
        assertThat(unsupported("It had 40.96 million enplanements.")).isEmpty();
    }

    @Test
    void aNumberThatNoToolResultHasIsFound() {
        assertThat(unsupported("The share is 18.4%.")).containsExactly("18.4");
        assertThat(unsupported("The score is 72.5 and the airport has 1,234,567 passengers."))
                .containsExactly("72.5", "1,234,567");
    }

    @Test
    void aWrongRoundingIsFound() {
        assertThat(unsupported("The score is 66.5.")).containsExactly("66.5");
    }

    @Test
    void smallWholeNumbersAreNotChecked() {
        assertThat(unsupported("1. BOS\n2. PWM\nThe top 5 airports. Use at most 3 tools.")).isEmpty();
    }

    @Test
    void aNumberOfTheQuestionIsAllowed() {
        assertThat(NumberCheck.unsupported("The limit of 1,500 miles gives the share.",
                List.of(TOOL_JSON, "What is the share at 1500 miles?"))).isEmpty();
    }

    @Test
    void aNumberInsideAWordOrCodeIsNotSplit() {
        assertThat(unsupported("The airport is B6 with code A320.")).isEmpty();
    }
}
