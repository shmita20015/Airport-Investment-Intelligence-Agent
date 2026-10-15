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

    /** Checks that a share can be shown as a rounded percentage. */
    @Test
    void aShareCanBeShownAsARoundedPercentage() {
        assertThat(unsupported("The long-haul share is 14.9%.")).isEmpty();
    }

    /** Checks that a value can be rounded. */
    @Test
    void aValueCanBeRounded() {
        assertThat(unsupported("The score is 67.0 and the rank is 12.")).isEmpty();
        assertThat(unsupported("The score is 67.")).isEmpty();
    }

    /** Checks that a large number can be shown with commas or words. */
    @Test
    void aLargeNumberCanBeShownWithCommasOrWords() {
        assertThat(unsupported("It had 40,956,673 enplanements.")).isEmpty();
        assertThat(unsupported("It had about 41 million enplanements.")).isEmpty();
        assertThat(unsupported("It had 40.96 million enplanements.")).isEmpty();
    }

    /** Checks that a number that no tool result has is found. */
    @Test
    void aNumberThatNoToolResultHasIsFound() {
        assertThat(unsupported("The share is 18.4%.")).containsExactly("18.4");
        assertThat(unsupported("The score is 72.5 and the airport has 1,234,567 passengers."))
                .containsExactly("72.5", "1,234,567");
    }

    /** Checks that a wrong rounding is found. */
    @Test
    void aWrongRoundingIsFound() {
        assertThat(unsupported("The score is 66.5.")).containsExactly("66.5");
    }

    /** Checks that small whole numbers are not checked. */
    @Test
    void smallWholeNumbersAreNotChecked() {
        assertThat(unsupported("1. BOS\n2. PWM\nThe top 5 airports. Use at most 3 tools.")).isEmpty();
    }

    /** Checks that a number of the question is allowed. */
    @Test
    void aNumberOfTheQuestionIsAllowed() {
        assertThat(NumberCheck.unsupported("The limit of 1,500 miles gives the share.",
                List.of(TOOL_JSON, "What is the share at 1500 miles?"))).isEmpty();
    }

    /** Checks that a number with commas in a tool text is one number. */
    @Test
    void aNumberWithCommasInAToolTextIsOneNumber() {
        List<String> sources = List.of("{\"assumptions\":[\"at least 100,000 enplanements\"]}");

        assertThat(NumberCheck.unsupported("The size limit is 100,000 enplanements.", sources)).isEmpty();
        assertThat(NumberCheck.unsupported("The size limit is 100 enplanements.", sources)).containsExactly("100");
    }

    /** Checks that a number inside a word or code is not split. */
    @Test
    void aNumberInsideAWordOrCodeIsNotSplit() {
        assertThat(unsupported("The airport is B6 with code A320.")).isEmpty();
    }

    /** Checks that a hyphen in a name is not a minus sign. */
    @Test
    void aHyphenInANameIsNotAMinusSign() {
        List<String> sources = List.of("{\"sources\":[\"BTS T-100 Segment (US carriers), 2025\"],\"growth\":-0.056}");

        assertThat(NumberCheck.unsupported("The data is from the BTS T-100 table for 2025.", sources)).isEmpty();
        assertThat(NumberCheck.unsupported("The growth is -5.6% a year.", sources)).isEmpty();
    }

    /** Checks that a cut last decimal is accepted. */
    @Test
    void aCutLastDecimalIsAccepted() {
        List<String> sources = List.of("{\"value\":48.6454,\"score\":66.98}");

        assertThat(NumberCheck.unsupported("The value is 48.64.", sources)).isEmpty();
        assertThat(NumberCheck.unsupported("The value is 48.65.", sources)).isEmpty();
        assertThat(NumberCheck.unsupported("The value is 48.6.", sources)).isEmpty();
        assertThat(NumberCheck.unsupported("The value is 48.7.", sources)).containsExactly("48.7");
        assertThat(NumberCheck.unsupported("The value is 48.66.", sources)).containsExactly("48.66");
        assertThat(NumberCheck.unsupported("The value is 48.63.", sources)).containsExactly("48.63");
    }

    /** Checks that a whole number must be rounded. */
    @Test
    void aWholeNumberMustBeRounded() {
        List<String> sources = List.of("{\"score\":66.98}");

        assertThat(NumberCheck.unsupported("The score is 67.", sources)).isEmpty();
        assertThat(NumberCheck.unsupported("The score is 66.", sources)).containsExactly("66");
    }
}
