package com.example.airportagent.scoring;

import com.example.airportagent.domain.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The weights of the components for one kind of question.
 * The weights must be zero or more, and they must add up to 1.0.
 * The weights are heuristics. They are not fitted to data.
 */
public record WeightProfile(String name, Map<Component, Double> weights) {

    private static final double TOLERANCE = 1e-6;

    /** Checks the weights. Each weight is zero or more, and they add up to 1.0. */
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

    /** The weight of a component. A component that is not in the profile has the weight 0. */
    public double weight(Component component) {
        return weights.getOrDefault(component, 0.0);
    }

    /**
     * A new profile for a what-if question. The given weights are fixed. The other components share the rest
     * (1.0 minus the fixed weights) in the ratio of this profile. If the fixed weights are 1.0 or more, or if no
     * other component has a weight, the fixed weights are scaled to add up to 1.0 and the others get 0.
     *
     * @throws IllegalArgumentException if a weight is negative or not a number, or if all weights are zero
     */
    public WeightProfile withOverrides(String newName, Map<Component, Double> fixed) {
        double fixedSum = 0;
        for (Map.Entry<Component, Double> entry : fixed.entrySet()) {
            double value = entry.getValue();
            if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
                throw new IllegalArgumentException("The weight of " + entry.getKey() + " must be zero or more.");
            }
            fixedSum += value;
        }
        double othersBase = 0;
        for (Component component : Component.values()) {
            if (!fixed.containsKey(component)) {
                othersBase += weight(component);
            }
        }
        Map<Component, Double> result = new EnumMap<>(Component.class);
        if (fixedSum >= 1.0 - TOLERANCE || othersBase <= 0) {
            if (fixedSum <= 0) {
                throw new IllegalArgumentException("At least one weight must be more than zero.");
            }
            double total = fixedSum;
            fixed.forEach((component, value) -> result.put(component, value / total));
        } else {
            double rest = 1.0 - fixedSum;
            fixed.forEach(result::put);
            for (Component component : Component.values()) {
                if (!fixed.containsKey(component) && weight(component) > 0) {
                    result.put(component, rest * weight(component) / othersBase);
                }
            }
        }
        return new WeightProfile(newName, result);
    }
}
