package com.example.airportagent.service;

import com.example.airportagent.domain.Component;
import com.example.airportagent.scoring.WeightProfile;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomWeightsTest {

    private static final Offset<Double> TINY = Offset.offset(1e-9);
    private static final WeightProfile BASE = new WeightProfile("base", Map.of(
            Component.CONGESTION, 0.5, Component.GROWTH, 0.3, Component.SCALE, 0.2));

    /** Checks that empty text gives the profile without change. */
    @Test
    void emptyTextGivesTheProfileWithoutChange() {
        assertThat(CustomWeights.apply(BASE, null)).isSameAs(BASE);
        assertThat(CustomWeights.apply(BASE, "  ")).isSameAs(BASE);
        assertThat(CustomWeights.apply(BASE, " , ; ")).isSameAs(BASE);
    }

    /** Checks that a fraction a percentage and a number give the same weight. */
    @Test
    void aFractionAPercentageAndANumberGiveTheSameWeight() {
        for (String text : new String[] {"scale:0.1", "scale:10%", "scale:10", "Scale = 10", "SCALE=0.10"}) {
            assertThat(CustomWeights.apply(BASE, text).weight(Component.SCALE)).as(text).isCloseTo(0.1, TINY);
        }
    }

    /** Checks that a component name may have spaces or hyphens. */
    @Test
    void aComponentNameMayHaveSpacesOrHyphens() {
        WeightProfile custom = CustomWeights.apply(BASE, "load factor:0.2, terminal-strain:0.1");

        assertThat(custom.weight(Component.LOAD_FACTOR)).isCloseTo(0.2, TINY);
        assertThat(custom.weight(Component.TERMINAL_STRAIN)).isCloseTo(0.1, TINY);
    }

    /** Checks that several weights can be given with commas or semicolons. */
    @Test
    void severalWeightsCanBeGivenWithCommasOrSemicolons() {
        WeightProfile custom = CustomWeights.apply(BASE, "congestion:0.6; growth:0.4");

        assertThat(custom.weight(Component.CONGESTION)).isCloseTo(0.6, TINY);
        assertThat(custom.weight(Component.GROWTH)).isCloseTo(0.4, TINY);
        assertThat(custom.weight(Component.SCALE)).isEqualTo(0.0);
    }

    /** Checks that bad text gives a clear message. */
    @Test
    void badTextGivesAClearMessage() {
        assertThatThrownBy(() -> CustomWeights.apply(BASE, "speed:1")).hasMessageContaining("Unknown component");
        assertThatThrownBy(() -> CustomWeights.apply(BASE, "scale")).hasMessageContaining("component:weight");
        assertThatThrownBy(() -> CustomWeights.apply(BASE, "scale:abc")).hasMessageContaining("Cannot read the number");
        assertThatThrownBy(() -> CustomWeights.apply(BASE, "scale:150%")).hasMessageContaining("more than 100%");
        assertThatThrownBy(() -> CustomWeights.apply(BASE, "scale:-0.5")).hasMessageContaining("zero or more");
    }

}
