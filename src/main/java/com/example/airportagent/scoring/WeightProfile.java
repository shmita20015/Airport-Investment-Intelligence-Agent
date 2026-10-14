package com.example.airportagent.scoring;

import com.example.airportagent.domain.Component;

import java.util.Map;

/**
 * The weights of the components for one kind of question.
 * The weights must be zero or more, and they must add up to 1.0.
 * The weights are heuristics. They are not fitted to data.
 */
public record WeightProfile(String name, Map<Component, Double> weights) {

    private static final double TOLERANCE = 1e-6;

    public WeightProfile {
        weights = Map.copyOf(weights);
        double sum = 0;
        for (Map.Entry<Component, Double> entry : weights.entrySet()) {
            if (entry.getValue() < 0 || entry.getValue().isNaN()) {
                throw new IllegalArgumentException(
                        "Profile " + name + ": weight of " + entry.getKey() + " must be zero or more.");
            }
            sum += entry.getValue();
        }
        if (Math.abs(sum - 1.0) > TOLERANCE) {
            throw new IllegalArgumentException("Profile " + name + ": the weights add up to " + sum + ", not 1.0.");
        }
    }

    public double weight(Component component) {
        return weights.getOrDefault(component, 0.0);
    }
}
