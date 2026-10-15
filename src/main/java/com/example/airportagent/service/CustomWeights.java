package com.example.airportagent.service;

import com.example.airportagent.domain.Component;
import com.example.airportagent.scoring.WeightProfile;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the custom weights of a user for a what-if question, for example "scale:0" or "congestion:0.5, growth:0.3".
 * A weight is a fraction (0.25), a percentage (25%) or a number from 0 to 100 (25).
 */
final class CustomWeights {

    /** Utility class. It has no instance. */
    private CustomWeights() {}

    /** The names of the components that a user can weight, in lower case. */
    static List<String> componentNames() {
        return Arrays.stream(Component.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toList();
    }

    /**
     * Applies the custom weights to a profile. The named components get the given weights. The others share the rest.
     *
     * @param text the weights as component:weight, separated by commas. Empty text gives the profile without change.
     * @throws IllegalArgumentException with a clear message if the text is not valid
     */
    static WeightProfile apply(WeightProfile base, String text) {
        if (text == null || text.isBlank()) {
            return base;
        }
        Map<Component, Double> fixed = new EnumMap<>(Component.class);
        for (String part : text.split("[,;]")) {
            if (part.isBlank()) {
                continue;
            }
            String[] pair = part.trim().split("[:=]");
            if (pair.length != 2) {
                throw new IllegalArgumentException("Cannot read the weight \"" + part.trim()
                        + "\". Use the format component:weight, for example scale:0.");
            }
            fixed.put(component(pair[0]), weight(pair[1].trim()));
        }
        return fixed.isEmpty() ? base : base.withOverrides("custom", fixed);
    }

    /** The component for a name. Case, spaces and hyphens do not matter ("load factor" is LOAD_FACTOR). */
    private static Component component(String name) {
        try {
            return Component.valueOf(name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown component \"" + name.trim() + "\".");
        }
    }

    /** A weight as a fraction from 0 to 1. A value above 1 or a value with % is a percentage. */
    private static double weight(String text) {
        boolean percent = text.endsWith("%");
        try {
            double value = Double.parseDouble(percent ? text.substring(0, text.length() - 1).trim() : text);
            if (percent || value > 1.0) {
                value = value / 100.0;
            }
            if (value > 1.0) {
                throw new IllegalArgumentException("A weight cannot be more than 100%.");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot read the number \"" + text + "\" as a weight.");
        }
    }
}
