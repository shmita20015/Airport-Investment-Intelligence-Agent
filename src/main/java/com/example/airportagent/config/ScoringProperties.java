package com.example.airportagent.config;

import com.example.airportagent.domain.Component;
import com.example.airportagent.scoring.WeightProfile;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Scoring settings from application.yml. A reviewer can change them without a code change.
 * The weights are heuristics. See docs/DESIGN-full.md, section 6.4.
 */
@ConfigurationProperties(prefix = "airport.scoring")
public record ScoringProperties(
        Map<String, Map<Component, Double>> profiles,
        Map<Component, Double> unmetDemand,
        long minEnplanements,
        long minOntimeFlights,
        int defaultLongHaulMiles) {

    /** The weight profile with this name. The constructor of WeightProfile checks that the sum is 1.0. */
    public WeightProfile profile(String name) {
        Map<Component, Double> weights = profiles.get(name);
        if (weights == null) {
            throw new IllegalArgumentException("Unknown weight profile: " + name + ". Known: " + profiles.keySet());
        }
        return new WeightProfile(name, weights);
    }

    public WeightProfile unmetDemandProfile() {
        return new WeightProfile("unmet-demand", unmetDemand);
    }
}
