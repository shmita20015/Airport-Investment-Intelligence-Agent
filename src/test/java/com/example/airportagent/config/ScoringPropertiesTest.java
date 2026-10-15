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

    /** Checks that terminal profile has the design weights. */
    @Test
    void terminalProfileHasTheDesignWeights() {
        WeightProfile terminal = properties.profile("terminal");

        assertThat(terminal.weight(Component.CONGESTION)).isEqualTo(0.25);
        assertThat(terminal.weight(Component.GROWTH)).isEqualTo(0.20);
        assertThat(terminal.weight(Component.LONG_HAUL)).isEqualTo(0.10);
        assertThat(terminal.weight(Component.SCALE)).isEqualTo(0.15);
    }

    /** Checks that all profiles are valid. */
    @Test
    void allProfilesAreValid() {
        properties.profiles().keySet().forEach(name -> assertThat(properties.profile(name)).isNotNull());
        assertThat(properties.unmetDemandProfile().weight(Component.LOAD_FACTOR)).isEqualTo(0.40);
    }

    /** Checks that unknown profile gives a clear error. */
    @Test
    void unknownProfileGivesAClearError() {
        assertThatThrownBy(() -> properties.profile("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown weight profile");
    }

    @Autowired
    private com.example.airportagent.data.AirportDataset data;

    /** Checks that every state code of the regions exists in the data. */
    @Test
    void everyStateCodeOfTheRegionsExistsInTheData() {
        java.util.Set<String> states = data.airports().values().stream().map(a -> a.state()).collect(
                java.util.stream.Collectors.toSet());

        properties.regions().forEach((name, codes) -> assertThat(states).as(name).containsAll(codes));
    }

    /** Checks that the broad regions are made of their divisions. */
    @Test
    void theBroadRegionsAreMadeOfTheirDivisions() {
        assertThat(properties.regions().get("NORTHEAST")).containsAll(properties.regions().get("NEW_ENGLAND"))
                .containsAll(properties.regions().get("MID_ATLANTIC"));
        assertThat(properties.regions().get("LOWER_48")).hasSize(49).doesNotContain("AK", "HI");
    }

}
