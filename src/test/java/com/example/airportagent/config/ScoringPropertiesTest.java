package com.example.airportagent.config;

import com.example.airportagent.domain.Component;
import com.example.airportagent.scoring.WeightProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** This test starts the application. It checks that application.yml binds and that all profiles add up to 1.0. */
@SpringBootTest(properties = "spring.ai.google.genai.api-key=test-key-not-real")
class ScoringPropertiesTest {

    @Autowired
    private ScoringProperties properties;

    @Test
    void terminalProfileHasTheDesignWeights() {
        WeightProfile terminal = properties.profile("terminal");

        assertThat(terminal.weight(Component.CONGESTION)).isEqualTo(0.25);
        assertThat(terminal.weight(Component.GROWTH)).isEqualTo(0.20);
        assertThat(terminal.weight(Component.LONG_HAUL)).isEqualTo(0.10);
        assertThat(terminal.weight(Component.SCALE)).isEqualTo(0.15);
    }

    @Test
    void allProfilesAreValid() {
        properties.profiles().keySet().forEach(name -> assertThat(properties.profile(name)).isNotNull());
        assertThat(properties.unmetDemandProfile().weight(Component.LOAD_FACTOR)).isEqualTo(0.40);
    }

    @Test
    void unknownProfileGivesAClearError() {
        assertThatThrownBy(() -> properties.profile("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown weight profile");
    }

    @Test
    void sizeAndLimitSettingsAreRead() {
        assertThat(properties.minEnplanements()).isEqualTo(100_000);
        assertThat(properties.defaultLongHaulMiles()).isEqualTo(2500);
    }
}
