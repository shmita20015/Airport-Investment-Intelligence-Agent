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
}
