package com.example.airportagent.scoring;

import com.example.airportagent.domain.Component;
import org.junit.jupiter.api.Test;

import static com.example.airportagent.scoring.TestData.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightProfileTest {

    @Test
    void acceptsWeightsThatAddUpToOne() {
        WeightProfile p = profile("ok", Component.CONGESTION, 0.6, Component.GROWTH, 0.4);

        assertThat(p.weight(Component.CONGESTION)).isEqualTo(0.6);
        assertThat(p.weight(Component.LONG_HAUL)).isZero();
    }

    @Test
    void rejectsWeightsThatDoNotAddUpToOne() {
        assertThatThrownBy(() -> profile("bad", Component.CONGESTION, 0.6, Component.GROWTH, 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not 1.0");
    }

    @Test
    void rejectsNegativeWeights() {
        assertThatThrownBy(() -> profile("bad", Component.CONGESTION, 1.5, Component.GROWTH, -0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero or more");
    }

    private static final org.assertj.core.data.Offset<Double> TINY = org.assertj.core.data.Offset.offset(1e-9);

    private static WeightProfile terminal() {
        return new WeightProfile("terminal", java.util.Map.of(
                com.example.airportagent.domain.Component.CONGESTION, 0.25, com.example.airportagent.domain.Component.GROWTH, 0.20,
                com.example.airportagent.domain.Component.LOAD_FACTOR, 0.15, com.example.airportagent.domain.Component.TERMINAL_STRAIN, 0.15,
                com.example.airportagent.domain.Component.LONG_HAUL, 0.10, com.example.airportagent.domain.Component.SCALE, 0.15));
    }

    @Test
    void aZeroWeightRemovesAComponentAndTheOthersShareItsWeight() {
        WeightProfile custom = terminal().withOverrides("custom",
                java.util.Map.of(com.example.airportagent.domain.Component.SCALE, 0.0));

        assertThat(custom.weight(com.example.airportagent.domain.Component.SCALE)).isEqualTo(0.0);
        assertThat(custom.weight(com.example.airportagent.domain.Component.CONGESTION)).isCloseTo(0.25 / 0.85, TINY);
        assertThat(custom.weight(com.example.airportagent.domain.Component.GROWTH)).isCloseTo(0.20 / 0.85, TINY);
        assertThat(custom.weights().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, TINY);
        assertThat(custom.name()).isEqualTo("custom");
    }

    @Test
    void aFixedWeightKeepsItsValueAndTheOthersShareTheRestInTheRatioOfTheProfile() {
        WeightProfile custom = terminal().withOverrides("custom",
                java.util.Map.of(com.example.airportagent.domain.Component.CONGESTION, 0.5));

        assertThat(custom.weight(com.example.airportagent.domain.Component.CONGESTION)).isCloseTo(0.5, TINY);
        // The others had 0.75 together. They share 0.5. Growth had 0.20 of 0.75.
        assertThat(custom.weight(com.example.airportagent.domain.Component.GROWTH)).isCloseTo(0.5 * 0.20 / 0.75, TINY);
        assertThat(custom.weights().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, TINY);
    }

    @Test
    void fixedWeightsThatAddUpToOneLeaveTheOthersAtZero() {
        WeightProfile custom = terminal().withOverrides("custom", java.util.Map.of(
                com.example.airportagent.domain.Component.GROWTH, 0.5, com.example.airportagent.domain.Component.LOAD_FACTOR, 0.5));

        assertThat(custom.weight(com.example.airportagent.domain.Component.GROWTH)).isCloseTo(0.5, TINY);
        assertThat(custom.weight(com.example.airportagent.domain.Component.CONGESTION)).isEqualTo(0.0);
        assertThat(custom.weight(com.example.airportagent.domain.Component.SCALE)).isEqualTo(0.0);
    }

    @Test
    void fixedWeightsThatAddUpToMoreThanOneAreScaledDown() {
        WeightProfile custom = terminal().withOverrides("custom", java.util.Map.of(
                com.example.airportagent.domain.Component.GROWTH, 0.8, com.example.airportagent.domain.Component.LOAD_FACTOR, 0.8));

        assertThat(custom.weight(com.example.airportagent.domain.Component.GROWTH)).isCloseTo(0.5, TINY);
        assertThat(custom.weight(com.example.airportagent.domain.Component.LOAD_FACTOR)).isCloseTo(0.5, TINY);
    }

    @Test
    void aComponentThatTheProfileDoesNotUseCanGetAWeight() {
        WeightProfile general = new WeightProfile("general", java.util.Map.of(
                com.example.airportagent.domain.Component.CONGESTION, 0.6, com.example.airportagent.domain.Component.GROWTH, 0.4));

        WeightProfile custom = general.withOverrides("custom",
                java.util.Map.of(com.example.airportagent.domain.Component.LONG_HAUL, 0.2));

        assertThat(custom.weight(com.example.airportagent.domain.Component.LONG_HAUL)).isCloseTo(0.2, TINY);
        assertThat(custom.weight(com.example.airportagent.domain.Component.CONGESTION)).isCloseTo(0.48, TINY);
    }

    @Test
    void aBadOverrideIsRejected() {
        WeightProfile onlyScale = new WeightProfile("s", java.util.Map.of(com.example.airportagent.domain.Component.SCALE, 1.0));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> terminal().withOverrides("custom",
                        java.util.Map.of(com.example.airportagent.domain.Component.SCALE, -0.1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("zero or more");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> onlyScale.withOverrides("custom",
                        java.util.Map.of(com.example.airportagent.domain.Component.SCALE, 0.0)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("At least one weight");
    }
}
